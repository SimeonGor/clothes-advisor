ALTER TABLE app_user
    ADD CONSTRAINT app_user_login_valid CHECK (login ~ '[^[:space:]]' AND char_length(login) <= 100),
    ADD CONSTRAINT app_user_version_positive CHECK (version > 0);

ALTER TABLE app_user_history
    ADD CONSTRAINT app_user_history_login_valid CHECK (login ~ '[^[:space:]]' AND char_length(login) <= 100),
    ADD CONSTRAINT app_user_history_version_positive CHECK (version > 0);

ALTER TABLE wardrobe_item
    ADD CONSTRAINT wardrobe_item_name_valid CHECK (name ~ '[^[:space:]]' AND char_length(name) <= 300),
    ADD CONSTRAINT wardrobe_item_color_valid CHECK (color ~ '[^[:space:]]' AND char_length(color) <= 100),
    ADD CONSTRAINT wardrobe_item_material_valid CHECK (material ~ '[^[:space:]]' AND char_length(material) <= 100),
    ADD CONSTRAINT wardrobe_item_version_positive CHECK (version > 0);

ALTER TABLE wardrobe_item_history
    ADD CONSTRAINT wardrobe_item_history_name_valid CHECK (name ~ '[^[:space:]]' AND char_length(name) <= 300),
    ADD CONSTRAINT wardrobe_item_history_color_valid CHECK (color ~ '[^[:space:]]' AND char_length(color) <= 100),
    ADD CONSTRAINT wardrobe_item_history_material_valid CHECK (material ~ '[^[:space:]]' AND char_length(material) <= 100),
    ADD CONSTRAINT wardrobe_item_history_version_positive CHECK (version > 0);

ALTER TABLE outfit ADD CONSTRAINT outfit_name_valid CHECK (name ~ '[^[:space:]]' AND char_length(name) <= 300);
ALTER TABLE outfit_weather ADD CONSTRAINT outfit_weather_wind_nonnegative CHECK (wind_speed_mps >= 0);
ALTER TABLE outfit_rating ADD CONSTRAINT outfit_rating_version_positive CHECK (version > 0);
ALTER TABLE outfit_rating_history ADD CONSTRAINT outfit_rating_history_version_positive CHECK (version > 0);
ALTER TABLE wardrobe_item_photo ADD CONSTRAINT wardrobe_item_photo_size_valid CHECK (size_bytes > 0 AND size_bytes <= 10000000);

ALTER TABLE wardrobe_category
    ADD CONSTRAINT wardrobe_category_code_nonblank CHECK (code ~ '[^[:space:]]'),
    ADD CONSTRAINT wardrobe_category_name_nonblank CHECK (name ~ '[^[:space:]]');
ALTER TABLE precipitation_type
    ADD CONSTRAINT precipitation_type_code_nonblank CHECK (code ~ '[^[:space:]]'),
    ADD CONSTRAINT precipitation_type_name_nonblank CHECK (name ~ '[^[:space:]]');
