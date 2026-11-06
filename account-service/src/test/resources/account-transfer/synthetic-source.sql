-- Synthetic data only. Explicit values exercise lossless COPY, not application defaults.
SET TIME ZONE 'UTC';

INSERT INTO public.app_user
    (id, login, password_hash, role, status, version, created_at, modified_at)
VALUES
    (11, 'владелец', E'synthetic\towner\\hash\nonly', 'USER', 'BLOCKED', 3,
     '2024-01-01 01:02:03.123456+03', '2025-02-03 04:05:06.654321+03'),
    (29, 'stylist', 'synthetic-stylist-hash', 'STYLIST', 'ACTIVE', 2,
     '2024-02-01 00:00:00+00', '2025-02-03 00:00:00+00'),
    (47, 'admin', 'synthetic-admin-hash', 'ADMIN', 'ACTIVE', 1,
     '2024-03-01 00:00:00+00', '2024-03-01 00:00:00+00');

INSERT INTO public.app_user_history
    (user_id, version, login, role, status, modified_at, archived_at)
VALUES
    (11, 1, 'old-owner', 'USER', 'ACTIVE',
     '2024-01-01 01:02:03.123456+03', '2024-06-01 00:00:00+00'),
    (11, 2, 'владелец', 'USER', 'ACTIVE',
     '2024-06-01 00:00:00+00', '2025-02-03 04:05:06.654321+03'),
    (29, 1, 'stylist', 'USER', 'ACTIVE',
     '2024-02-01 00:00:00+00', '2025-02-03 00:00:00+00');

INSERT INTO public.access_grant (owner_id, stylist_id) VALUES (11, 29);
SELECT pg_catalog.setval('public.app_user_id_seq', 900, true);

INSERT INTO public.wardrobe_item
    (id, owner_id, category_id, name, color, material, version, created_at, modified_at)
SELECT 101, 11, id, 'Пальто', 'синий', 'шерсть', 2,
    '2024-04-01 00:00:00+00', '2025-01-01 00:00:00+00'
FROM public.wardrobe_category WHERE code = 'OUTERWEAR';

INSERT INTO public.wardrobe_item_history
    (wardrobe_item_id, version, name, category_id, color, material, modified_at, archived_at)
SELECT 101, 1, 'Coat', category_id, color, material,
    '2024-04-01 00:00:00+00', '2025-01-01 00:00:00+00'
FROM public.wardrobe_item WHERE id = 101;

INSERT INTO public.wardrobe_item_photo
    (id, wardrobe_item_id, s3_key, content_type, size_bytes, created_at)
VALUES (201, 101, 'synthetic/coat.jpg', 'image/jpeg', 1024, '2024-04-01 00:00:00+00');

INSERT INTO public.outfit (id, owner_id, author_id, source, name, created_at)
VALUES
    (301, 11, 29, 'STYLIST', 'Current outfit', '2025-01-01 00:00:00+00'),
    (399, 11, 29, 'STYLIST', 'Deleted outfit', '2024-05-01 00:00:00+00');

INSERT INTO public.outfit_item (outfit_id, wardrobe_item_id, position) VALUES (301, 101, 0);
INSERT INTO public.outfit_weather
    (outfit_id, temperature_c, precipitation_type_id, wind_speed_mps)
SELECT 301, 7.5, id, 3.25 FROM public.precipitation_type WHERE code = 'RAIN';

INSERT INTO public.outfit_rating
    (outfit_id, stylist_id, vote, version, created_at, modified_at)
VALUES
    (301, 29, 'LIKE', 2, '2025-01-01 00:00:00+00', '2025-01-02 00:00:00+00'),
    (399, 29, 'LIKE', 2, '2024-05-01 00:00:00+00', '2024-05-02 00:00:00+00');

INSERT INTO public.outfit_rating_history
    (outfit_id, stylist_id, version, vote, modified_at, archived_at)
VALUES
    (301, 29, 1, 'DISLIKE', '2025-01-01 00:00:00+00', '2025-01-02 00:00:00+00'),
    (399, 29, 1, 'DISLIKE', '2024-05-01 00:00:00+00', '2024-05-02 00:00:00+00');
DELETE FROM public.outfit WHERE id = 399;
