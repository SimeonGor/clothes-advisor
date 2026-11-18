BEGIN READ ONLY;
SET LOCAL statement_timeout = '5s';
SELECT count(*) FILTER (WHERE published_at IS NULL) AS pending,
       count(*) FILTER (WHERE published_at IS NULL AND next_attempt_at <= now()) AS due_now,
       extract(epoch FROM now() - min(created_at) FILTER (WHERE published_at IS NULL))::bigint AS oldest_pending_seconds,
       count(*) FILTER (WHERE published_at IS NULL AND attempts > 1) AS pending_retried,
       count(*) FILTER (WHERE published_at IS NOT NULL AND attempts > 1) AS published_after_retry
FROM outbox_event;
COMMIT;
