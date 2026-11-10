package ru.itmo.clothesadvisor.recommendationservice.messaging

import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test", "request-store-test", "kafka")
@DirtiesContext
class RecommendationDeliveryTests {
    @Value("\${app.kafka.groups.ai-selection}") private lateinit var selectionGroup: String
    @Autowired private lateinit var kafka: KafkaTemplate<String, String>
    @Autowired private lateinit var listeners: KafkaListenerEndpointRegistry
    @Autowired private lateinit var requests: RecommendationRequestListener

    @Test
    fun `database failure holds offset until commit then restart and terminal replay preserve identities`() {
        val id = UUID.randomUUID()
        val month = LocalDate.parse(value("SELECT date_trunc('month', CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date"))
        val accepted = "${month}T00:00:00Z"
        val key = "$month/$id"
        val eventId = UUID.randomUUID()
        val wire = """
            {"eventId":"$eventId","eventType":"RecommendationRequested","schemaVersion":1,
             "occurredAt":"$accepted","aggregateId":"$id","payload":{
             "requestId":"$id","requestMonth":"$month","ownerId":7,"goal":"OUTFIT","inputVersion":1,
             "acceptedAt":"$accepted","startDeadline":"${month}T00:15:00Z","input":{
               "stylePreferences":{"preferredColorsAndMaterials":"blue","avoidances":"wool","additionalWishes":""},
               "weather":{"temperatureC":12.1234567890123456789,"windSpeedMps":0,"precipitationCode":"NONE","precipitationName":"None"},
               "candidates":[{"itemId":1,"name":"рубашка 👕 \\u0000","categoryCode":"TOP","categoryName":"Top","color":"blue","material":"cotton",
                              "photoId":2,"fileId":"${UUID.randomUUID()}","contentType":"image/jpeg","sizeBytes":123}]}}}
        """.trimIndent()
        sql("CREATE SEQUENCE blocked_delivery")
        sql("""CREATE FUNCTION fail_accept() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN PERFORM nextval('blocked_delivery'); RAISE EXCEPTION 'simulated unavailable store'; END $$""")
        sql("CREATE TRIGGER fail_accept BEFORE INSERT ON recommendation_request FOR EACH ROW EXECUTE FUNCTION fail_accept()")
        val traceId = "0123456789abcdef0123456789abcdef"
        val traceparent = "00-$traceId-1020304050607080-01"
        val nulName = wire.replace("""\\u0000""", """\u0000""")
        val invalidOffsets = listOf(
            send(key, nulName),
            send(key, wire.replace("None", """rain\u0000""")),
            send(key, wire.replace("wool", """wool\u0000""")),
            send(key, wire.replace("12.1234567890123456789", "1e200000")),
            send(key, wire.replace("12.1234567890123456789", "1e-20000")),
            send(key, wire.replace(accepted, "-1000000000-01-01T00:00:00Z")),
            send(key, wire.replace("${month}T00:15:00Z", "+300000-01-01T00:00:00Z")),
        )
        val first = send(key, wire, traceparent)
        await().atMost(Duration.ofSeconds(45)).until { value("SELECT is_called FROM blocked_delivery") == "t" }
        assertThat(committed()).isLessThanOrEqualTo(first)
        assertThat(value("SELECT count(*) FROM recommendation_request")).isEqualTo("0")
        sql("DROP TRIGGER fail_accept ON recommendation_request")
        awaitCommit(first)
        requests.receive(ConsumerRecord(TOPIC, 0, invalidOffsets.first(), key, nulName.toByteArray()))
        for (offset in invalidOffsets) {
            assertThat(value("""SELECT count(*) FROM kafka_delivery_rejection WHERE topic='$TOPIC'
                AND partition_id=0 AND record_offset=$offset AND reason='invalid_input'""")).isEqualTo("1")
        }
        assertThat(value("SELECT input->'candidates'->0->>'name' FROM recommendation_request")).isEqualTo("""рубашка 👕 \u0000""")
        val admissionParent = value("SELECT traceparent FROM recommendation_request")
        assertThat(admissionParent).matches("00-$traceId-[0-9a-f]{16}-01").isNotEqualTo(traceparent)
        assertThat(value("SELECT state FROM recommendation_request")).isEqualTo("RECEIVED")
        assertThat(value("SELECT input->'weather'->>'temperatureC' FROM recommendation_request")).isEqualTo("12.1234567890123456789")

        listeners.stop()
        val duplicate = send(key, wire, "malformed")
        // A stopped process may already have committed its work without having committed its offset.
        requests.receive(ConsumerRecord(TOPIC, 0, duplicate, key, wire.toByteArray()))
        assertThat(committed()).isEqualTo(duplicate)
        listeners.start()
        awaitCommit(duplicate)
        assertThat(value("SELECT count(*) FROM recommendation_request")).isEqualTo("1")
        assertThat(value("SELECT count(*) FROM outbox_event")).isEqualTo("0")
        val conflict = send(key, wire.replace("\"ownerId\":7", "\"ownerId\":8"))
        val unknownVersion = send(key, wire.replace("\"schemaVersion\":1", "\"schemaVersion\":2"))
        val unknownField = send(key, wire.replace("\"sizeBytes\":123", "\"sizeBytes\":123,\"imageBase64\":\"private\""))
        val oldMonth = month.minusMonths(6)
        val closed = send("$oldMonth/$id", wire.replace(month.toString(), oldMonth.toString()))
        val wrongKey = send("wrong", wire)
        awaitCommit(wrongKey)
        for ((offset, reason) in listOf(conflict to "input_conflict", unknownVersion to "unsupported_schema_version",
            unknownField to "invalid_input", closed to "request_period_closed", wrongKey to "request_key_mismatch")
        ) {
            assertThat(value("SELECT reason FROM kafka_delivery_rejection WHERE record_offset = $offset")).isEqualTo(reason)
        }
        assertThat(value("SELECT count(*) FROM recommendation_request WHERE state = 'RECEIVED' AND owner_id = 7")).isEqualTo("1")

        val resultEvent = UUID.randomUUID()
        val completed = Instant.parse("${month}T00:14:33.123456Z")
        sql("""UPDATE recommendation_request SET state='SUCCEEDED', result_event_id='$resultEvent', completed_at='$completed',
               result='{"itemIds":[1],"explanation":"chosen"}' WHERE request_id='$id'""")
        awaitCommit(send(key, wire))
        awaitPublished(resultEvent)
        assertThat(value("SELECT created_at = '$completed' FROM outbox_event")).isEqualTo("t")
        assertThat(value("SELECT payload->>'outcome' FROM outbox_event")).isEqualTo("SUCCEEDED")
        assertThat(value("SELECT payload->>'explanation' FROM outbox_event")).isEqualTo("chosen")
        awaitCommit(send(key, wire))
        awaitPublished(resultEvent)
        assertThat(value("SELECT attempts FROM outbox_event")).isEqualTo("2")
        sql("DELETE FROM outbox_event")
        awaitCommit(send(key, wire))
        awaitPublished(resultEvent)
        assertThat(value("SELECT created_at = '$completed' FROM outbox_event")).isEqualTo("t")
        assertThat(value("SELECT state FROM recommendation_request")).isEqualTo("SUCCEEDED")
        assertThat(value("SELECT traceparent FROM recommendation_request")).isEqualTo(admissionParent)
        KafkaConsumer(mapOf("bootstrap.servers" to broker.bootstrapServers, "enable.auto.commit" to false),
            StringDeserializer(), StringDeserializer()).use { consumer ->
            val partition = TopicPartition("ai-selection.execution-events", 0)
            consumer.assign(listOf(partition))
            consumer.seekToBeginning(listOf(partition))
            val records = buildList {
                val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
                while (size < 3 && System.nanoTime() < deadline) addAll(consumer.poll(Duration.ofMillis(300)).toList())
            }
            assertThat(records.map { it.value() }.distinct()).hasSize(1)
            val parents = records.map { it.headers().lastHeader("traceparent").value().toString(Charsets.US_ASCII) }
            assertThat(parents).hasSize(3).doesNotHaveDuplicates().allSatisfy {
                assertThat(it).matches("00-$traceId-[0-9a-f]{16}-01").isNotEqualTo(admissionParent)
            }
        }
    }

    private fun send(key: String, message: String, traceparent: String? = null): Long {
        val record = ProducerRecord(TOPIC, 0, key, message)
        traceparent?.let { record.headers().add("traceparent", it.toByteArray(Charsets.US_ASCII)) }
        return kafka.send(record).get(15, TimeUnit.SECONDS).recordMetadata.offset()
    }

    private fun committed() = admin.listConsumerGroupOffsets(selectionGroup)
        .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS)[TopicPartition(TOPIC, 0)]?.offset() ?: -1

    private fun awaitCommit(offset: Long) {
        await().atMost(Duration.ofSeconds(30)).until { committed() > offset }
    }

    private fun awaitPublished(event: UUID) {
        await().atMost(Duration.ofSeconds(15)).until {
            value("SELECT count(*) FROM outbox_event WHERE event_id='$event' AND published_at IS NOT NULL") == "1"
        }
    }

    private fun sql(statement: String) = connection().use { db -> db.createStatement().use { it.execute(statement) } }
    private fun value(statement: String) = connection().use { db ->
        db.createStatement().use { it.executeQuery(statement).use { row -> check(row.next()); row.getString(1) } }
    }

    private fun connection() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    companion object {
        private const val TOPIC = "ai-selection.requests"
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }
        private val broker = KafkaContainer("apache/kafka@sha256:5cc2a2fd93fa2687b44015eee04fb2c3edd9e526bd64bf8bec5ff1e268772e0e")
            .apply { start() }
        private val admin = Admin.create(mapOf("bootstrap.servers" to broker.bootstrapServers)).apply {
            createTopics(listOf(NewTopic(TOPIC, 1, 1.toShort()), NewTopic("ai-selection.execution-events", 1, 1.toShort())))
                .all().get(30, TimeUnit.SECONDS)
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.kafka.bootstrap-servers", broker::getBootstrapServers)
            registry.add("spring.kafka.properties.security.protocol") { "PLAINTEXT" }
            registry.add("spring.kafka.properties.sasl.jaas.config") { "" }
            registry.add("spring.kafka.properties.ssl.truststore.certificates") { "" }
            registry.add("spring.r2dbc.url") { "r2dbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/${postgres.databaseName}" }
            registry.add("spring.r2dbc.username", postgres::getUsername)
            registry.add("spring.r2dbc.password", postgres::getPassword)
            registry.add("spring.flyway.url", postgres::getJdbcUrl)
            registry.add("spring.flyway.user", postgres::getUsername)
            registry.add("spring.flyway.password", postgres::getPassword)
        }

        @JvmStatic
        @AfterAll
        fun stop() {
            admin.close()
            broker.stop()
            postgres.stop()
        }
    }
}
