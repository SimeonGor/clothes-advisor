-- An old wardrobe writer omits file_id. Include COPY text delimiters, CR/LF,
-- backslashes, a standalone \. inside the key, and a non-UTC source timestamp.
INSERT INTO public.wardrobe_item_photo
    (id, wardrobe_item_id, s3_key, content_type, size_bytes, created_at) VALUES
    (11, 70, E'старый/ёлка,"a"\t\r\n\\.\nфото.jpg', 'image/jpeg', 12, '2025-02-01 02:03:04.123456+03'),
    (21, 70, 'empty-directory-name//p.png', 'image/png', 1, '2026-01-01 00:00:00+00'),
    (31, 70, 'legacy/pinned.png', 'image/png', 19, '2025-06-07 08:09:10+00');

UPDATE public.wardrobe_item_photo SET file_id = '00000000-0000-4000-8000-000000000031' WHERE id = 31;
