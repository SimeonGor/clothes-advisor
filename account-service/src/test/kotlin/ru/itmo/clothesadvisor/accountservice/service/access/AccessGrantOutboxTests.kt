package ru.itmo.clothesadvisor.accountservice.service.access

import com.jayway.jsonpath.JsonPath
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.r2dbc.core.DatabaseClient
import ru.itmo.clothesadvisor.accountservice.config.PostgresIntegrationTest
import ru.itmo.clothesadvisor.accountservice.messaging.AccessOutboxRelay

@SpringBootTest
internal class AccessGrantOutboxTests : PostgresIntegrationTest() {
    @Autowired private lateinit var service: AccessGrantService
    @Autowired private lateinit var database: DatabaseClient

    @Test
    fun `failed outbox insert rolls back the grant and no-op transitions do not produce events`() {
        val owner = user("USER")
        val stylist = user("STYLIST")
        jdbc.execute("ALTER TABLE outbox_event ADD CONSTRAINT fail_grant CHECK (type <> 'AccessGranted')")
        try {
            assertThatThrownBy { service.grant(owner, stylist).block() }
                .isInstanceOf(DataIntegrityViolationException::class.java)
        } finally {
            jdbc.execute("ALTER TABLE outbox_event DROP CONSTRAINT fail_grant")
        }
        assertThat(jdbc.queryForList("SELECT * FROM access_grant")).isEmpty()
        repeat(2) { service.grant(owner, stylist).block() }
        repeat(2) { service.revoke(owner, stylist).block() }
        assertThat(jdbc.queryForList("SELECT type FROM outbox_event", String::class.java))
            .containsExactlyInAnyOrder("AccessGranted", "AccessRevoked")
    }

    @Test
    fun `retry after Kafka ack and failed publication mark repeats the same envelope`() {
        val owner = user("USER")
        val stylist = user("STYLIST")
        service.grant(owner, stylist).block()
        val sent = mutableListOf<ProducerRecord<String, String>>()
        val producer = mock<Producer<String, String>>()
        `when`(producer.send(any())).thenAnswer {
            sent.add(it.getArgument(0))
            CompletableFuture.completedFuture(null)
        }
        val relay = AccessOutboxRelay(database, producer)
        jdbc.execute("ALTER TABLE outbox_event ADD CONSTRAINT fail_mark CHECK (published_at IS NULL)")
        try {
            assertThatThrownBy(relay::publishPending).isInstanceOf(DataIntegrityViolationException::class.java)
        } finally {
            jdbc.execute("ALTER TABLE outbox_event DROP CONSTRAINT fail_mark")
        }
        jdbc.update("UPDATE outbox_event SET next_attempt_at = now()")
        relay.publishPending()
        relay.publishPending()
        assertThat(sent.map { it.topic() }).containsExactly("stylist-access.events", "stylist-access.events")
        assertThat(sent.map { it.key() }).containsOnly("access/$owner/$stylist")
        assertThat(sent.last().value()).isEqualTo(sent.first().value())
        val envelope = JsonPath.parse(sent.first().value())
        assertThat(envelope.read<Map<String, Any>>("$").keys)
            .containsExactlyInAnyOrder("eventId", "eventType", "schemaVersion", "occurredAt", "aggregateId", "payload")
        assertThat(UUID.fromString(envelope.read("$.eventId"))).isEqualTo(
            jdbc.queryForObject("SELECT event_id FROM outbox_event WHERE published_at IS NOT NULL", UUID::class.java),
        )
        assertThat(envelope.read<String>("$.aggregateId")).isEqualTo("$owner/$stylist")
        assertThat(envelope.read<String>("$.eventType")).isEqualTo("AccessGranted")
        assertThat(envelope.read<Int>("$.schemaVersion")).isEqualTo(1)
        Instant.parse(envelope.read<String>("$.occurredAt"))
        val payload = envelope.read<Map<String, Number>>("$.payload")
        assertThat(payload.mapValues { it.value.toLong() }).isEqualTo(mapOf("ownerId" to owner, "stylistId" to stylist))
    }

    private fun user(role: String): Long = jdbc.queryForObject(
        """INSERT INTO app_user (login, password_hash, role, status, version)
            VALUES (?, 'hash', ?::user_role, 'ACTIVE', 1) RETURNING id""",
        Long::class.java, "access-${UUID.randomUUID()}", role,
    )!!
}
