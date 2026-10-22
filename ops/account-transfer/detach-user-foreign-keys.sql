-- Stage 17 only: move into a NEW Flyway migration at the coordinated cutover.
-- Stage 16 rehearsal: execute in a transaction on synthetic data, then ROLLBACK.
ALTER TABLE public.wardrobe_item DROP CONSTRAINT wardrobe_item_owner_id_fkey;
ALTER TABLE public.outfit DROP CONSTRAINT outfit_author_id_fkey;
ALTER TABLE public.outfit DROP CONSTRAINT outfit_owner_id_fkey;
ALTER TABLE public.outfit_rating DROP CONSTRAINT outfit_rating_stylist_id_fkey;
ALTER TABLE public.outfit_rating_history DROP CONSTRAINT outfit_rating_history_stylist_id_fkey;
