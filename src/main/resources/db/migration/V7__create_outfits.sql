CREATE TYPE outfit_source AS ENUM ('USER', 'STYLIST', 'AI');

CREATE TABLE outfit (
    id BIGSERIAL PRIMARY KEY,
    owner_id BIGINT NOT NULL REFERENCES app_user (id),
    author_id BIGINT NOT NULL REFERENCES app_user (id),
    source outfit_source NOT NULL,
    name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX outfit_owner_id_id_idx ON outfit (owner_id, id DESC);

CREATE TABLE outfit_item (
    outfit_id BIGINT NOT NULL REFERENCES outfit (id) ON DELETE CASCADE,
    wardrobe_item_id BIGINT NOT NULL REFERENCES wardrobe_item (id) ON DELETE RESTRICT,
    position INTEGER NOT NULL,
    PRIMARY KEY (outfit_id, wardrobe_item_id),
    UNIQUE (outfit_id, position)
);

CREATE INDEX outfit_item_wardrobe_item_id_idx ON outfit_item (wardrobe_item_id);

CREATE TABLE outfit_weather (
    outfit_id BIGINT PRIMARY KEY REFERENCES outfit (id) ON DELETE CASCADE,
    temperature_c NUMERIC NOT NULL,
    precipitation_type_id BIGINT NOT NULL REFERENCES precipitation_type (id),
    wind_speed_mps NUMERIC NOT NULL
);
