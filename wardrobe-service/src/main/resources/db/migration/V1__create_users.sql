CREATE TYPE user_role AS ENUM ('USER', 'STYLIST', 'ADMIN');
CREATE TYPE user_status AS ENUM ('ACTIVE', 'BLOCKED');

CREATE TABLE app_user (
    id BIGSERIAL PRIMARY KEY,
    login TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    role user_role NOT NULL,
    status user_status NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    modified_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE app_user_history (
    user_id BIGINT NOT NULL REFERENCES app_user (id),
    version BIGINT NOT NULL,
    login TEXT NOT NULL,
    role user_role NOT NULL,
    status user_status NOT NULL,
    modified_at TIMESTAMPTZ NOT NULL,
    archived_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, version)
);
