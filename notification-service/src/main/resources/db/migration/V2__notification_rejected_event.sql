CREATE TABLE notification_rejected_event (
    topic TEXT NOT NULL,
    partition_id INTEGER NOT NULL,
    record_offset BIGINT NOT NULL,
    reason TEXT NOT NULL CHECK (reason IN ('INVALID_EVENT', 'UNSUPPORTED_VERSION')),
    PRIMARY KEY (topic, partition_id, record_offset)
);
