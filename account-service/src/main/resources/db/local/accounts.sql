INSERT INTO app_user (login, password_hash, role, status, version)
VALUES ('user', '!', 'USER', 'ACTIVE', 1),
       ('stylist', '!', 'STYLIST', 'ACTIVE', 1),
       ('admin', '!', 'ADMIN', 'ACTIVE', 1)
ON CONFLICT (login) DO NOTHING;
