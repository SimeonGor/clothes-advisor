ALTER TABLE outbox_event ADD COLUMN traceparent varchar(55);
ALTER TABLE ai_job ADD COLUMN finalization_traceparent varchar(55);
