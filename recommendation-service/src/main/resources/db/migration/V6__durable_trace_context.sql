ALTER TABLE recommendation_request
    ADD COLUMN traceparent varchar(55),
    ADD COLUMN execution_traceparent varchar(55);
