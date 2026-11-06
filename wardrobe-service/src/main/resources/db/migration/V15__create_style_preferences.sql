CREATE TABLE style_preferences (
    owner_id BIGINT PRIMARY KEY,
    preferred_colors_and_materials VARCHAR(2000) NOT NULL DEFAULT '',
    avoidances VARCHAR(2000) NOT NULL DEFAULT '',
    additional_wishes VARCHAR(2000) NOT NULL DEFAULT '',
    version BIGINT NOT NULL CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE style_preferences_history (
    owner_id BIGINT NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    preferred_colors_and_materials VARCHAR(2000) NOT NULL DEFAULT '',
    avoidances VARCHAR(2000) NOT NULL DEFAULT '',
    additional_wishes VARCHAR(2000) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL,
    modified_at TIMESTAMPTZ NOT NULL,
    archived_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (owner_id, version)
);
