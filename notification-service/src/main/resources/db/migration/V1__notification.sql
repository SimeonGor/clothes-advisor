CREATE TABLE notification (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_event_id UUID NOT NULL,
    recipient_id BIGINT NOT NULL CHECK (recipient_id > 0),
    type TEXT NOT NULL,
    message TEXT NOT NULL,
    resource_type TEXT,
    resource_id TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    read_at TIMESTAMPTZ,
    UNIQUE (source_event_id, recipient_id),
    CHECK ((resource_type IS NULL) = (resource_id IS NULL))
);

CREATE INDEX notification_recipient_page ON notification (recipient_id, created_at DESC, id DESC);
CREATE INDEX notification_recipient_unread ON notification (recipient_id) WHERE read_at IS NULL;
