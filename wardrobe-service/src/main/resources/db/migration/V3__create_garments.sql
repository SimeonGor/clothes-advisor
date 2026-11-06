CREATE TABLE garment (
    id BIGSERIAL PRIMARY KEY,
    owner_id BIGINT NOT NULL REFERENCES app_user (id),
    category_id BIGINT NOT NULL REFERENCES garment_category (id),
    name TEXT NOT NULL,
    color TEXT NOT NULL,
    material TEXT NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    modified_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX garment_owner_id_id_idx ON garment (owner_id, id);

CREATE TABLE garment_history (
    garment_id BIGINT NOT NULL,
    version BIGINT NOT NULL,
    name TEXT NOT NULL,
    category_id BIGINT NOT NULL REFERENCES garment_category (id),
    color TEXT NOT NULL,
    material TEXT NOT NULL,
    modified_at TIMESTAMPTZ NOT NULL,
    archived_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (garment_id, version)
);
