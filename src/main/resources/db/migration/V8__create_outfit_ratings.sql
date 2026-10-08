CREATE TYPE rating_vote AS ENUM ('LIKE', 'DISLIKE');

CREATE TABLE outfit_rating (
    outfit_id BIGINT NOT NULL REFERENCES outfit (id) ON DELETE CASCADE,
    stylist_id BIGINT NOT NULL REFERENCES app_user (id),
    vote rating_vote NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    modified_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (outfit_id, stylist_id)
);

CREATE TABLE outfit_rating_history (
    outfit_id BIGINT NOT NULL,
    stylist_id BIGINT NOT NULL REFERENCES app_user (id),
    version BIGINT NOT NULL,
    vote rating_vote NOT NULL,
    modified_at TIMESTAMPTZ NOT NULL,
    archived_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (outfit_id, stylist_id, version)
);

CREATE INDEX outfit_rating_history_page_idx
    ON outfit_rating_history (outfit_id, modified_at DESC, stylist_id DESC, version DESC);
