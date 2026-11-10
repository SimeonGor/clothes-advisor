package ru.itmo.clothesadvisor.repository.idempotency

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.idempotency.IdempotencyCommand

internal interface IdempotencyCommandRepository : Repository<IdempotencyCommand, Long> {
    @Query("""
        INSERT INTO idempotency_command (actor_id, operation, command_key, fingerprint)
        VALUES (:actorId, :operation, :key, :fingerprint)
        ON CONFLICT (actor_id, operation, command_key) DO NOTHING RETURNING *
    """)
    fun claim(actorId: Long, operation: String, key: String, fingerprint: String): IdempotencyCommand?

    @Query("""
        SELECT * FROM idempotency_command
        WHERE actor_id = :actorId AND operation = :operation AND command_key = :key
    """)
    fun findCommand(actorId: Long, operation: String, key: String): IdempotencyCommand

    @Modifying
    @Query("""
        UPDATE idempotency_command SET state = 'SUCCEEDED', response_json = :json,
            response_status = :status, modified_at = CURRENT_TIMESTAMP
        WHERE id = :id AND state = 'RUNNING'
    """)
    fun succeed(id: Long, json: String, status: Int): Int

    @Modifying
    @Query("""
        UPDATE idempotency_command SET state = 'FAILED', modified_at = CURRENT_TIMESTAMP
        WHERE id = :id AND state = 'RUNNING'
    """)
    fun fail(id: Long): Int
}
