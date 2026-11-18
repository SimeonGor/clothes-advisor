BEGIN READ ONLY;
SET LOCAL statement_timeout = '5s';
SELECT count(*) AS notifications FROM notification;
SELECT topic, reason, count(*) AS rejected_offsets
FROM notification_rejected_event
GROUP BY topic, reason
ORDER BY topic, reason;
COMMIT;
