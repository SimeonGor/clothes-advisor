BEGIN READ ONLY;
SET LOCAL statement_timeout = '5s';
SELECT count(*) FILTER (WHERE status = 'QUEUED') AS queued,
       count(*) FILTER (WHERE status = 'RUNNING') AS running,
       extract(epoch FROM now() - min(created_at) FILTER (WHERE status IN ('QUEUED','RUNNING')))::bigint AS oldest_active_seconds,
       count(*) FILTER (WHERE status = 'FAILED') AS failed,
       count(*) FILTER (WHERE status = 'UNKNOWN') AS unknown
FROM ai_job;
COMMIT;
