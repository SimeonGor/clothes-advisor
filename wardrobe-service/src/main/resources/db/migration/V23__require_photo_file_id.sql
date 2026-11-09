ALTER TABLE public.wardrobe_item_photo
    ALTER COLUMN file_id SET NOT NULL,
    DROP COLUMN s3_key;
