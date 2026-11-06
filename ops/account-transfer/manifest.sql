-- Read-only; never call nextval during verification. Freeze all writers first.
SET TIME ZONE 'UTC';

SELECT 'app_user', count(*),
    encode(sha256(convert_to(
        COALESCE(jsonb_agg(to_jsonb(u) ORDER BY u.id), '[]'::jsonb)::text,
        'UTF8'
    )), 'hex')
FROM public.app_user AS u;

SELECT 'app_user_history', count(*),
    encode(sha256(convert_to(
        COALESCE(jsonb_agg(to_jsonb(h) ORDER BY h.user_id, h.version), '[]'::jsonb)::text,
        'UTF8'
    )), 'hex')
FROM public.app_user_history AS h;

SELECT 'access_grant', count(*),
    encode(sha256(convert_to(
        COALESCE(jsonb_agg(to_jsonb(g) ORDER BY g.owner_id, g.stylist_id), '[]'::jsonb)::text,
        'UTF8'
    )), 'hex')
FROM public.access_grant AS g;

SELECT 'app_user_id_seq', last_value, is_called FROM public.app_user_id_seq;
