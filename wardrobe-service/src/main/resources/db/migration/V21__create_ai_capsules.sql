CREATE TYPE capsule_source AS ENUM ('USER', 'AI');

ALTER TABLE capsule
    ADD COLUMN source capsule_source NOT NULL DEFAULT 'USER',
    ADD COLUMN occasion VARCHAR(300),
    ADD COLUMN preferred_style VARCHAR(300),
    ADD COLUMN explanation VARCHAR(2000) CHECK (explanation ~ '[^[:space:]]'),
    ADD CONSTRAINT capsule_ai_explanation CHECK (source <> 'AI' OR explanation IS NOT NULL);

CREATE TABLE capsule_weather (
    capsule_id BIGINT PRIMARY KEY REFERENCES capsule (id) ON DELETE CASCADE,
    temperature_c NUMERIC NOT NULL,
    precipitation_type_id BIGINT NOT NULL REFERENCES precipitation_type (id),
    wind_speed_mps NUMERIC NOT NULL CHECK (wind_speed_mps >= 0)
);
