CREATE TYPE stylist_request_status AS ENUM ('PENDING', 'CANCELLED');

CREATE TABLE stylist_request (
    id BIGSERIAL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    stylist_id BIGINT NOT NULL,
    wishes VARCHAR(2000) NOT NULL DEFAULT '',
    status stylist_request_status NOT NULL DEFAULT 'PENDING',
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX stylist_request_owner_id_id_idx ON stylist_request (owner_id, id DESC);
CREATE INDEX stylist_request_stylist_owner_id_idx ON stylist_request (stylist_id, owner_id, id DESC);

CREATE TABLE stylist_request_weather (
    request_id BIGINT PRIMARY KEY REFERENCES stylist_request (id),
    temperature_c NUMERIC NOT NULL,
    precipitation_type_id BIGINT NOT NULL REFERENCES precipitation_type (id),
    wind_speed_mps NUMERIC NOT NULL CHECK (wind_speed_mps >= 0)
);

CREATE TABLE stylist_request_history (
    request_id BIGINT NOT NULL REFERENCES stylist_request (id),
    version BIGINT NOT NULL CHECK (version > 0),
    owner_id BIGINT NOT NULL,
    stylist_id BIGINT NOT NULL,
    wishes VARCHAR(2000) NOT NULL,
    status stylist_request_status NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    modified_at TIMESTAMPTZ NOT NULL,
    archived_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (request_id, version)
);
