CREATE TABLE wardrobe_item_photo (
    id BIGSERIAL PRIMARY KEY,
    wardrobe_item_id BIGINT NOT NULL REFERENCES wardrobe_item (id) ON DELETE CASCADE,
    s3_key TEXT NOT NULL UNIQUE,
    content_type TEXT NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX wardrobe_item_photo_item_id_id_idx ON wardrobe_item_photo (wardrobe_item_id, id);
