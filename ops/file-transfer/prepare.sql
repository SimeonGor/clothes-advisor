-- Run with psql --single-transaction, after draining all photo writers.
LOCK TABLE public.wardrobe_item_photo IN SHARE ROW EXCLUSIVE MODE;

DO $$
DECLARE
    invalid_photo bigint;
BEGIN
    SELECT id INTO invalid_photo FROM public.wardrobe_item_photo
    WHERE content_type NOT IN ('image/jpeg', 'image/png')
       OR size_bytes NOT BETWEEN 1 AND 10000000
    ORDER BY id LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'Photo % has incompatible MIME or size', invalid_photo;
    END IF;
END
$$;

UPDATE public.wardrobe_item_photo SET file_id = gen_random_uuid()
WHERE file_id IS NULL;
