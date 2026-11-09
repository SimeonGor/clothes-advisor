CREATE TEMP TABLE source_photo (
    photo_id bigint PRIMARY KEY,
    wardrobe_item_id bigint NOT NULL,
    file_id uuid NOT NULL UNIQUE,
    object_key text NOT NULL UNIQUE,
    content_type text NOT NULL,
    size_bytes bigint NOT NULL,
    created_at timestamptz NOT NULL
) ON COMMIT DROP;
