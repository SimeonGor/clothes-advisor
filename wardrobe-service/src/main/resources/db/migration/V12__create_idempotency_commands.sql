CREATE TABLE idempotency_command (
    id BIGSERIAL PRIMARY KEY,
    actor_id BIGINT NOT NULL,
    operation VARCHAR(40) NOT NULL,
    command_key VARCHAR(255) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    state VARCHAR(10) NOT NULL DEFAULT 'RUNNING' CHECK (state IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    response_json TEXT,
    response_status INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (actor_id, operation, command_key),
    CHECK ((state = 'SUCCEEDED') = (response_json IS NOT NULL AND response_status IS NOT NULL))
);
