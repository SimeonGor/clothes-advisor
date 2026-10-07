CREATE TABLE garment_category (
    id BIGSERIAL PRIMARY KEY,
    code TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL
);

CREATE TABLE precipitation_type (
    id BIGSERIAL PRIMARY KEY,
    code TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL
);

INSERT INTO garment_category (code, name) VALUES
    ('TOP', 'Верх'),
    ('BOTTOM', 'Низ'),
    ('ONE_PIECE', 'Платья и комбинезоны'),
    ('OUTERWEAR', 'Верхняя одежда'),
    ('FOOTWEAR', 'Обувь'),
    ('ACCESSORIES', 'Аксессуары');

INSERT INTO precipitation_type (code, name) VALUES
    ('NONE', 'Нет'),
    ('RAIN', 'Дождь'),
    ('SNOW', 'Снег'),
    ('SLEET', 'Дождь со снегом');
