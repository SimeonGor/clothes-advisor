-- Keep only the fingerprint after clearing the private job input and result text.
ALTER TABLE ai_job ADD COLUMN provider_result_digest BYTEA;

CREATE TABLE kafka_delivery_rejection (
    topic TEXT NOT NULL,
    partition_id INTEGER NOT NULL,
    record_offset BIGINT NOT NULL,
    reason TEXT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (topic, partition_id, record_offset)
);
