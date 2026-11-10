CREATE TABLE ai_execution_slot (
    connection_id text PRIMARY KEY CHECK (connection_id = 'personal'),
    state text NOT NULL CHECK (state IN ('FREE', 'RUNNING', 'PAUSED')),
    execution_id uuid,
    mode text CHECK (mode IN ('SYNC', 'ASYNC')),
    phase text CHECK (phase IN ('PREPARING', 'CALL_STARTED')),
    request_month date,
    request_id uuid,
    started_at timestamptz,
    modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (
        (state = 'FREE' AND execution_id IS NULL AND mode IS NULL AND phase IS NULL
            AND started_at IS NULL AND request_month IS NULL AND request_id IS NULL)
        OR (state <> 'FREE' AND execution_id IS NOT NULL AND mode IS NOT NULL AND phase IS NOT NULL
            AND started_at IS NOT NULL AND (state <> 'PAUSED' OR phase = 'CALL_STARTED')
            AND ((mode = 'ASYNC' AND request_month IS NOT NULL AND request_id IS NOT NULL)
                OR (mode = 'SYNC' AND request_month IS NULL AND request_id IS NULL)))
    )
);
INSERT INTO ai_execution_slot (connection_id, state) VALUES ('personal', 'FREE');

CREATE INDEX recommendation_request_queue_idx ON recommendation_request (accepted_at, request_month, request_id)
    WHERE state = 'RECEIVED';

-- One transition and one stable event identity, including expiration of an unclaimed request.
CREATE FUNCTION finish_recommendation(m date, id uuid, expected_state text, outcome text, error_code text, selection jsonb)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    event uuid := gen_random_uuid();
    finished timestamptz := clock_timestamp();
    owner bigint;
BEGIN
    IF outcome NOT IN ('SUCCEEDED','FAILED','UNKNOWN')
        OR (outcome = 'SUCCEEDED') <> (selection IS NOT NULL)
        OR (outcome = 'SUCCEEDED') <> (error_code IS NULL) THEN
        RAISE EXCEPTION 'Invalid recommendation outcome';
    END IF;

    UPDATE recommendation_request r
    SET state = outcome, result = selection, error_code = finish_recommendation.error_code,
        result_event_id = event, completed_at = finished, modified_at = finished, version = version + 1
    WHERE request_month = m AND request_id = id AND state = expected_state
        AND expected_state IN ('RECEIVED','RUNNING')
    RETURNING owner_id INTO STRICT owner;

    INSERT INTO outbox_event(event_id, type, aggregate_id, payload, created_at)
    VALUES (event, 'RecommendationFinished', id::text,
        jsonb_build_object('requestId', id, 'requestMonth', m, 'ownerId', owner, 'outcome', outcome)
        || COALESCE(selection, jsonb_build_object('errorCode', error_code)), finished);
END $$;

-- Called by both the owner and operator recovery. The lock and UUID fence every transition.
CREATE FUNCTION resolve_ai_execution(expected uuid, outcome text, error_code text, selection jsonb)
RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE
    slot ai_execution_slot%ROWTYPE;
BEGIN
    SELECT * INTO slot FROM ai_execution_slot
    WHERE connection_id = 'personal' AND state = 'RUNNING' AND execution_id = expected FOR UPDATE;
    IF NOT FOUND THEN RETURN false; END IF;

    IF outcome = 'REQUEUE' THEN
        IF slot.phase <> 'PREPARING' THEN RAISE EXCEPTION 'Provider may already have been called'; END IF;
        IF slot.mode = 'ASYNC' THEN
            UPDATE recommendation_request SET state = 'RECEIVED', modified_at = clock_timestamp(), version = version + 1
            WHERE request_month = slot.request_month AND request_id = slot.request_id AND state = 'RUNNING';
            IF NOT FOUND THEN RAISE EXCEPTION 'Lost running recommendation'; END IF;
        END IF;
    ELSE
        IF outcome NOT IN ('SUCCEEDED','FAILED','UNKNOWN')
            OR (outcome IN ('SUCCEEDED','UNKNOWN') AND slot.phase <> 'CALL_STARTED') THEN
            RAISE EXCEPTION 'Invalid execution transition';
        END IF;
        IF slot.mode = 'ASYNC' THEN
            IF slot.phase = 'PREPARING' AND EXISTS (
                SELECT 1 FROM recommendation_request WHERE request_month = slot.request_month
                    AND request_id = slot.request_id AND start_deadline <= clock_timestamp()
            ) THEN error_code := 'queue_expired'; END IF;
            PERFORM finish_recommendation(slot.request_month, slot.request_id, 'RUNNING', outcome, error_code, selection);
        END IF;
    END IF;

    IF outcome = 'UNKNOWN' THEN
        UPDATE ai_execution_slot SET state = 'PAUSED', modified_at = clock_timestamp()
        WHERE execution_id = expected;
    ELSE
        UPDATE ai_execution_slot SET state = 'FREE', execution_id = NULL, mode = NULL, phase = NULL,
            request_month = NULL, request_id = NULL, started_at = NULL, modified_at = clock_timestamp()
        WHERE execution_id = expected;
    END IF;
    RETURN true;
END $$;

-- Only run manually, after stopping the old owner. Never issue the old call again after CALL_STARTED.
CREATE FUNCTION recover_ai_execution(expected uuid) RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE p text;
BEGIN
    SELECT phase INTO p FROM ai_execution_slot WHERE state = 'RUNNING' AND execution_id = expected FOR UPDATE;
    IF NOT FOUND THEN RETURN false; END IF;
    IF p = 'PREPARING' THEN RETURN resolve_ai_execution(expected, 'REQUEUE', NULL, NULL); END IF;
    RETURN resolve_ai_execution(expected, 'UNKNOWN', 'ai_outcome_unknown', NULL);
END $$;

CREATE FUNCTION resume_ai_execution(expected uuid) RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE slot ai_execution_slot%ROWTYPE;
BEGIN
    SELECT * INTO slot FROM ai_execution_slot WHERE execution_id = expected AND state = 'PAUSED' FOR UPDATE;
    IF NOT FOUND THEN RETURN false; END IF;
    IF slot.mode = 'ASYNC' THEN
        UPDATE recommendation_request SET reviewed_at = clock_timestamp(), modified_at = clock_timestamp(), version = version + 1
        WHERE request_month = slot.request_month AND request_id = slot.request_id AND state = 'UNKNOWN';
        IF NOT FOUND THEN RAISE EXCEPTION 'Missing unknown recommendation'; END IF;
    END IF;
    UPDATE ai_execution_slot SET state = 'FREE', execution_id = NULL, mode = NULL, phase = NULL,
        request_month = NULL, request_id = NULL, started_at = NULL, modified_at = clock_timestamp() WHERE execution_id = expected;
    RETURN true;
END $$;
