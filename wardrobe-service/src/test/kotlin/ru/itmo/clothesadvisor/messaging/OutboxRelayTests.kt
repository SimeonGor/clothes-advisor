package ru.itmo.clothesadvisor.messaging

import java.time.Duration
import java.util.UUID
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper

class OutboxRelayTests {
    @Test
    fun `only committed work is published and broker ack before mark replays identical bytes`() {
        PostgreSQLContainer("postgres:18-alpine").use { postgres ->
            KafkaContainer("apache/kafka@sha256:5cc2a2fd93fa2687b44015eee04fb2c3edd9e526bd64bf8bec5ff1e268772e0e").use { broker ->
                postgres.start()
                broker.start()
                val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                Flyway.configure().dataSource(source).load().migrate()
                val database = JdbcTemplate(source)
                val factory = DefaultKafkaProducerFactory<String, String>(
                    mapOf("bootstrap.servers" to broker.bootstrapServers, "acks" to "all", "enable.idempotence" to true),
                    StringSerializer(), StringSerializer(),
                )
                try {
                    val relay = OutboxRelay(database, KafkaTemplate(factory))
                    val eventId = UUID.randomUUID()
                    val aggregateId = UUID.randomUUID()
                    source.connection.use { connection ->
                        connection.autoCommit = false
                        connection.prepareStatement(
                            """
                            INSERT INTO outbox_event (event_id, type, schema_version, aggregate_id, payload)
                            VALUES (?, 'RecommendationRequested', 1, ?, jsonb_build_object('requestMonth', '2026-11-01', 'requestId', ?::uuid))
                            """,
                        ).use { insert ->
                            insert.setObject(1, eventId)
                            insert.setString(2, aggregateId.toString())
                            insert.setObject(3, aggregateId)
                            insert.executeUpdate()
                        }
                        relay.publishNext()
                        connection.commit()
                    }
                    assertThat(database.queryForObject("SELECT attempts FROM outbox_event", Int::class.java)).isZero()

                    // Simulate losing the database after the broker confirms its write.
                    database.execute(
                        """CREATE FUNCTION fail_mark() RETURNS trigger LANGUAGE plpgsql AS $$
                            BEGIN RAISE EXCEPTION 'simulated post-send failure'; END $$""",
                    )
                    database.execute("CREATE TRIGGER fail_mark BEFORE UPDATE OF published_at ON outbox_event FOR EACH ROW EXECUTE FUNCTION fail_mark()")
                    assertThatThrownBy { relay.publishNext() }.hasMessageContaining("simulated post-send failure")
                    database.execute("DROP TRIGGER fail_mark ON outbox_event")
                    relay.publishNext()
                    assertThat(database.queryForObject("SELECT attempts FROM outbox_event WHERE published_at IS NULL", Int::class.java)).isOne()
                    database.update("UPDATE outbox_event SET next_attempt_at = clock_timestamp()")
                    relay.publishNext()
                    assertThat(database.queryForObject("SELECT attempts FROM outbox_event WHERE published_at IS NOT NULL", Int::class.java)).isEqualTo(2)

                    KafkaConsumer(
                        mapOf("bootstrap.servers" to broker.bootstrapServers, "enable.auto.commit" to false),
                        StringDeserializer(), StringDeserializer(),
                    ).use { consumer ->
                        val topic = "ai-selection.requests"
                        val partitions = consumer.partitionsFor(topic).map { TopicPartition(topic, it.partition()) }
                        consumer.assign(partitions)
                        consumer.seekToBeginning(partitions)
                        val records = buildList {
                            val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
                            while (size < 2 && System.nanoTime() < deadline) addAll(consumer.poll(Duration.ofMillis(300)).toList())
                        }
                        assertThat(records).hasSize(2)
                        assertThat(records.map { it.key() }).containsOnly("2026-11-01/$aggregateId")
                        assertThat(records[0].value()).isEqualTo(records[1].value())
                        val envelope = JsonMapper.builder().build().readTree(records[0].value())
                        assertThat(envelope.path("eventId").asString()).isEqualTo(eventId.toString())
                        assertThat(envelope.path("occurredAt").asString()).endsWith("Z")
                        assertThat(envelope.path("aggregateId").asString()).isEqualTo(aggregateId.toString())
                    }
                } finally {
                    factory.destroy()
                }
            }
        }
    }
}
