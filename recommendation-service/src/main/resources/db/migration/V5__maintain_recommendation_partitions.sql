-- A committed close may outlive the service that initiated it.
-- The pending month is always the month immediately preceding the barrier.
ALTER TABLE request_retention ADD COLUMN pending_drop_xid xid8;

-- V1 creates exactly one conventional name and monthly range per partition.
-- Fail closed on catalog drift, including a detached or unrelated table with the expected name.
CREATE FUNCTION recommendation_partition_exists(m date) RETURNS boolean
LANGUAGE plpgsql STABLE SET DateStyle = 'ISO, YMD' SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    expected_name text := 'recommendation_request_' || to_char(m, 'YYYY_MM');
    expected_bound text := format('FOR VALUES FROM (%L) TO (%L)', m, (m + interval '1 month')::date);
    child oid := to_regclass(format('public.%I', expected_name));
    attached oid;
BEGIN
    SELECT c.oid INTO attached FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
    WHERE i.inhparent = 'public.recommendation_request'::regclass
      AND pg_get_expr(c.relpartbound, c.oid) = expected_bound;
    IF attached IS DISTINCT FROM child THEN
        RAISE EXCEPTION 'Unexpected partition name, parent or bounds for %', m;
    END IF;
    RETURN child IS NOT NULL;
END $$;

-- One call is one autocommit step. Every replica and the operator use this same entry point.
CREATE FUNCTION maintain_recommendation_partitions()
RETURNS TABLE(action text, partition_month date, reason text)
LANGUAGE plpgsql SET DateStyle = 'ISO, YMD' SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    now_month date := date_trunc('month', clock_timestamp() AT TIME ZONE 'UTC')::date;
    keep_from date := (now_month - interval '3 months')::date;
    oldest date;
    pending date;
    barrier_transaction xid8;
    child_name text;
    m date;
BEGIN
    SELECT closed_before_month, pending_drop_xid INTO STRICT oldest, barrier_transaction
    FROM request_retention WHERE singleton FOR UPDATE;

    IF barrier_transaction IS NOT NULL THEN
        IF barrier_transaction = pg_current_xact_id() THEN
            RAISE EXCEPTION 'Commit the retention barrier before dropping a partition';
        END IF;
        pending := (oldest - interval '1 month')::date;
        IF recommendation_partition_exists(pending) THEN
            child_name := 'recommendation_request_' || to_char(pending, 'YYYY_MM');
            EXECUTE format('ALTER TABLE public.recommendation_request DETACH PARTITION public.%I', child_name);
            EXECUTE format('DROP TABLE public.%I', child_name);
        END IF;
        UPDATE request_retention SET pending_drop_xid = NULL WHERE singleton;
        RETURN QUERY SELECT 'removed'::text, pending, NULL::text;
        RETURN;
    END IF;

    IF oldest < keep_from THEN
        PERFORM recommendation_partition_exists(oldest);
        SELECT CASE
        WHEN EXISTS (SELECT FROM recommendation_request WHERE request_month = oldest
                     AND state NOT IN ('SUCCEEDED', 'FAILED', 'UNKNOWN')) THEN 'unfinished_request'
        WHEN EXISTS (SELECT FROM recommendation_request WHERE request_month = oldest
                     AND applied_at IS NULL) THEN 'awaiting_application'
        WHEN EXISTS (SELECT FROM recommendation_request WHERE request_month = oldest
                     AND state = 'UNKNOWN' AND reviewed_at IS NULL) THEN 'unreviewed_unknown'
        WHEN EXISTS (SELECT FROM outbox_event e JOIN recommendation_request r
                     ON e.payload->>'requestId' = r.request_id::text AND e.payload->>'requestMonth' = r.request_month::text
                     WHERE r.request_month = oldest AND e.published_at IS NULL) THEN 'unpublished_event'
        END INTO reason;
    END IF;

    -- During catch-up precreate the normal window first. When an old month is blocked,
    -- intermediate months are still open for late deliveries and must be routable too.
    m := CASE WHEN reason IS NOT NULL THEN oldest ELSE greatest(oldest, keep_from) END;
    WHILE m <= now_month + interval '1 month' LOOP
        IF NOT recommendation_partition_exists(m) THEN
            EXECUTE format('CREATE TABLE public.%I PARTITION OF public.recommendation_request FOR VALUES FROM (%L) TO (%L)',
                'recommendation_request_' || to_char(m, 'YYYY_MM'), m, (m + interval '1 month')::date);
        END IF;
        m := (m + interval '1 month')::date;
    END LOOP;

    IF oldest >= keep_from THEN
        RETURN QUERY SELECT 'ready'::text, oldest, NULL::text;
        RETURN;
    END IF;
    IF reason IS NOT NULL THEN
        RETURN QUERY SELECT 'blocked'::text, oldest, reason;
        RETURN;
    END IF;

    UPDATE request_retention SET closed_before_month = (oldest + interval '1 month')::date,
        pending_drop_xid = pg_current_xact_id() WHERE singleton;
    RETURN QUERY SELECT 'closed'::text, oldest, NULL::text;
END $$;
