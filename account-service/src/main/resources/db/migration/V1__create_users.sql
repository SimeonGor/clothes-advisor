CREATE TYPE user_role AS ENUM ('USER', 'STYLIST', 'ADMIN');
CREATE TYPE user_status AS ENUM ('ACTIVE', 'BLOCKED');

CREATE TABLE app_user (
    id BIGSERIAL PRIMARY KEY,
    login TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    role user_role NOT NULL,
    status user_status NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT app_user_login_valid CHECK (login ~ '[^[:space:]]' AND char_length(login) <= 100),
    CONSTRAINT app_user_version_positive CHECK (version > 0)
);

CREATE TABLE app_user_history (
    user_id BIGINT NOT NULL REFERENCES app_user (id),
    version BIGINT NOT NULL,
    login TEXT NOT NULL,
    role user_role NOT NULL,
    status user_status NOT NULL,
    modified_at TIMESTAMPTZ NOT NULL,
    archived_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, version),
    CONSTRAINT app_user_history_login_valid
        CHECK (login ~ '[^[:space:]]' AND char_length(login) <= 100),
    CONSTRAINT app_user_history_version_positive CHECK (version > 0)
);
