package ru.itmo.clothesadvisor.config

import org.assertj.core.api.Assertions.assertThat
import org.springframework.jdbc.core.JdbcTemplate

internal fun JdbcTemplate.assertDomainEvents(aggregateId: Long, payload: String, vararg types: String) {
    assertThat(queryForList("SELECT type FROM outbox_event", String::class.java))
        .containsExactlyInAnyOrder(*types)
    assertThat(queryForObject(
        """SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND payload = CAST(? AS jsonb)
           AND schema_version = 1 AND published_at IS NULL""",
        Int::class.java, aggregateId.toString(), payload,
    )).isEqualTo(types.size)
}
