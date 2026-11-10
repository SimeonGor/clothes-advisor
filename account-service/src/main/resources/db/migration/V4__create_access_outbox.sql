CREATE TABLE outbox_event (
    event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type TEXT NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    aggregate_id TEXT NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX outbox_event_pending_idx ON outbox_event (next_attempt_at, created_at, event_id)
    WHERE published_at IS NULL;
