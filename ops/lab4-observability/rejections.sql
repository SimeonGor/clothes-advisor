BEGIN READ ONLY;
SET LOCAL statement_timeout = '5s';
SELECT topic, reason, count(*) AS rejected_offsets
FROM kafka_delivery_rejection
GROUP BY topic, reason
ORDER BY topic, reason;
COMMIT;
