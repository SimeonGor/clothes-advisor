CREATE TYPE calendar_entry_status AS ENUM ('PLANNED', 'WORN');

CREATE TABLE calendar_entry (
    id BIGSERIAL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    calendar_date DATE NOT NULL,
    outfit_id BIGINT NOT NULL REFERENCES outfit (id) ON DELETE RESTRICT,
    status calendar_entry_status NOT NULL,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (owner_id, calendar_date, outfit_id)
);

CREATE TABLE calendar_entry_history (
    entry_id BIGINT NOT NULL,
    version BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    calendar_date DATE NOT NULL,
    outfit_id BIGINT NOT NULL,
    status calendar_entry_status NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    modified_at TIMESTAMPTZ NOT NULL,
    archived_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (entry_id, version)
);
