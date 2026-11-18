CREATE TABLE request_retention (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    closed_before_month date NOT NULL CHECK (extract(day FROM closed_before_month) = 1)
);

CREATE TABLE recommendation_request (
    request_month date NOT NULL CHECK (extract(day FROM request_month) = 1),
    request_id uuid NOT NULL,
    owner_id bigint NOT NULL,
    input_version integer NOT NULL,
    input jsonb NOT NULL,
    goal text NOT NULL CHECK (goal IN ('OUTFIT', 'CAPSULE')),
    accepted_at timestamptz NOT NULL,
    start_deadline timestamptz NOT NULL,
    state text NOT NULL DEFAULT 'RECEIVED'
        CHECK (state IN ('RECEIVED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
    prompt_text text,
    prompt_version bigint,
    model text,
    result jsonb,
    error_code text,
    result_event_id uuid,
    completed_at timestamptz,
    applied_at timestamptz,
    reviewed_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (request_month, request_id)
) PARTITION BY RANGE (request_month);

DO $$
DECLARE
    current_month date := date_trunc('month', CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date;
    first_month date := (current_month - interval '3 months')::date;
    partition_month date := first_month;
    following_month date;
BEGIN
    INSERT INTO request_retention (closed_before_month) VALUES (first_month);
    WHILE partition_month <= current_month + interval '1 month' LOOP
        following_month := (partition_month + interval '1 month')::date;
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF recommendation_request FOR VALUES FROM (%L) TO (%L)',
            'recommendation_request_' || to_char(partition_month, 'YYYY_MM'),
            partition_month, following_month
        );
        partition_month := following_month;
    END LOOP;
END $$;
