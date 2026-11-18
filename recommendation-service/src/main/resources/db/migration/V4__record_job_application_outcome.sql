ALTER TABLE recommendation_request ADD COLUMN application_outcome TEXT
    CHECK (application_outcome IN ('SUCCEEDED', 'FAILED', 'UNKNOWN'));
