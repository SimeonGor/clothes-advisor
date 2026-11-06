ALTER TYPE stylist_request_status ADD VALUE 'ANSWERED';

CREATE TABLE stylist_request_answer (
    request_id BIGINT PRIMARY KEY REFERENCES stylist_request (id),
    outfit_id BIGINT NOT NULL UNIQUE REFERENCES outfit (id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
