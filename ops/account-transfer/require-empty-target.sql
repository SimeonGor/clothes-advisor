-- Run only through psql --single-transaction, before the data dump.
LOCK TABLE public.app_user, public.app_user_history, public.access_grant
    IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM public.app_user)
        OR EXISTS (SELECT 1 FROM public.app_user_history)
        OR EXISTS (SELECT 1 FROM public.access_grant) THEN
        RAISE EXCEPTION 'Account transfer requires an empty target';
    END IF;
END
$$;
