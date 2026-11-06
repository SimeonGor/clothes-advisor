CREATE TABLE capsule (
    id BIGSERIAL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    name VARCHAR(300) NOT NULL CHECK (name ~ '[^[:space:]]'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX capsule_owner_id_id_idx ON capsule (owner_id, id DESC);

CREATE TABLE capsule_item (
    capsule_id BIGINT NOT NULL REFERENCES capsule (id) ON DELETE CASCADE,
    wardrobe_item_id BIGINT NOT NULL REFERENCES wardrobe_item (id) ON DELETE RESTRICT,
    position INTEGER NOT NULL,
    PRIMARY KEY (capsule_id, wardrobe_item_id),
    UNIQUE (capsule_id, position)
);

CREATE INDEX capsule_item_wardrobe_item_id_idx ON capsule_item (wardrobe_item_id);
