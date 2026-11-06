CREATE TYPE wardrobe_item_availability AS ENUM ('AVAILABLE', 'IN_LAUNDRY', 'STORED');

ALTER TABLE wardrobe_item
    ADD COLUMN availability wardrobe_item_availability NOT NULL DEFAULT 'AVAILABLE';

ALTER TABLE wardrobe_item_history
    ADD COLUMN availability wardrobe_item_availability NOT NULL DEFAULT 'AVAILABLE';
