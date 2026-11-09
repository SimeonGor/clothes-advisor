-- A single read-only transaction produces a deterministic UTF-8 COPY text stream.
SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY;
SET LOCAL TIME ZONE 'UTC';
SET LOCAL DateStyle = 'ISO, YMD';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM public.wardrobe_item_photo WHERE file_id IS NULL) THEN
        RAISE EXCEPTION 'Photo without file_id: run prepare under the writer freeze';
    END IF;
END
$$;

COPY (
    SELECT id, wardrobe_item_id, file_id, s3_key, content_type, size_bytes, created_at
    FROM public.wardrobe_item_photo ORDER BY id
) TO STDOUT WITH (FORMAT text, ENCODING 'UTF8');
