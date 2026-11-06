-- Apply only after account transfer and manifest verification at the coordinated cutover.
ALTER TABLE public.wardrobe_item DROP CONSTRAINT wardrobe_item_owner_id_fkey;
ALTER TABLE public.outfit DROP CONSTRAINT outfit_author_id_fkey;
ALTER TABLE public.outfit DROP CONSTRAINT outfit_owner_id_fkey;
ALTER TABLE public.outfit_rating DROP CONSTRAINT outfit_rating_stylist_id_fkey;
ALTER TABLE public.outfit_rating_history DROP CONSTRAINT outfit_rating_history_stylist_id_fkey;
