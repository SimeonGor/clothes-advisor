BEGIN READ ONLY;
SET LOCAL statement_timeout = '5s';
SELECT count(*) FILTER (WHERE state = 'RECEIVED') AS waiting,
       extract(epoch FROM now() - min(accepted_at) FILTER (WHERE state = 'RECEIVED'))::bigint AS oldest_wait_seconds,
       count(*) FILTER (WHERE state = 'RECEIVED' AND start_deadline <= now()) AS waiting_past_deadline,
       count(*) FILTER (WHERE state = 'RUNNING') AS running,
       count(*) FILTER (WHERE state = 'FAILED') AS failed,
       count(*) FILTER (WHERE state = 'UNKNOWN') AS unknown,
       count(*) FILTER (WHERE state = 'UNKNOWN' AND reviewed_at IS NULL) AS unknown_unreviewed,
       count(*) FILTER (WHERE completed_at IS NOT NULL AND applied_at IS NULL) AS awaiting_application
FROM recommendation_request;

SELECT state, mode, phase,
       extract(epoch FROM now() - started_at)::bigint AS slot_age_seconds
FROM ai_execution_slot
WHERE connection_id = 'personal';
COMMIT;
