CREATE TABLE access_grant (
    owner_id BIGINT NOT NULL REFERENCES app_user (id),
    stylist_id BIGINT NOT NULL REFERENCES app_user (id),
    PRIMARY KEY (owner_id, stylist_id)
);

CREATE INDEX access_grant_stylist_owner_idx ON access_grant (stylist_id, owner_id);
