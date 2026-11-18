CREATE TABLE ai_job (
    job_id UUID PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    type TEXT NOT NULL CHECK (type IN ('OUTFIT_SELECTION', 'OUTFIT_REPLACEMENT', 'CAPSULE_SELECTION')),
    request_month DATE NOT NULL,
    input JSONB,
    status TEXT NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
    result_type TEXT CHECK (result_type IN ('OUTFIT', 'CAPSULE')),
    result_id BIGINT,
    error_code TEXT,
    start_deadline TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (request_month = date_trunc('month', created_at AT TIME ZONE 'UTC')::date),
    CHECK (status NOT IN ('QUEUED', 'RUNNING') OR input IS NOT NULL),
    CHECK ((status = 'SUCCEEDED') = (result_id IS NOT NULL)),
    CHECK ((status = 'SUCCEEDED') = (result_type IS NOT NULL)),
    CHECK ((status IN ('FAILED', 'UNKNOWN')) = (error_code IS NOT NULL))
);

CREATE INDEX ai_job_owner_created_idx ON ai_job (owner_id, created_at DESC, job_id DESC);
CREATE INDEX ai_job_active_idx ON ai_job (status) WHERE status IN ('QUEUED', 'RUNNING');

CREATE TABLE outbox_event (
    event_id UUID PRIMARY KEY,
    type TEXT NOT NULL,
    schema_version INTEGER NOT NULL,
    aggregate_id TEXT NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX outbox_event_pending_idx ON outbox_event (next_attempt_at, created_at, event_id)
    WHERE published_at IS NULL;
