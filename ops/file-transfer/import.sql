-- Target writers and simultaneous imports serialize here until verification commits.
LOCK TABLE public.file_object IN SHARE ROW EXCLUSIVE MODE;

INSERT INTO public.file_object (file_id, object_key, content_type, size_bytes, created_at)
SELECT s.file_id, s.object_key, s.content_type, s.size_bytes, s.created_at
FROM pg_temp.source_photo s
WHERE NOT EXISTS (SELECT 1 FROM public.file_object f WHERE f.file_id = s.file_id);

DO $$
DECLARE
    conflicting_photo bigint;
BEGIN
    SELECT s.photo_id INTO conflicting_photo FROM pg_temp.source_photo s
    LEFT JOIN public.file_object f ON f.file_id = s.file_id
    WHERE ROW(f.object_key, f.content_type, f.size_bytes, f.created_at)
          IS DISTINCT FROM ROW(s.object_key, s.content_type, s.size_bytes, s.created_at)
    ORDER BY s.photo_id LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'Target file metadata does not match photo %', conflicting_photo;
    END IF;
END
$$;
