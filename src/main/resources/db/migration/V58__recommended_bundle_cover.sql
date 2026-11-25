-- The cover of the card of a recommended bundle (recommended bundle specification 4, the cover). The cover is one of the
-- photos of the bundle that is on the card, chosen by the rules of the cover and not by the order of the card (the
-- photos are in the order of the time), so it is kept here: the app shows it on the card, and the bundle that is made of
-- the recommendation takes it as its representative photo (photo box 4.2.2, 4.1.4). Until now the photo with the card
-- position 1 was read as the cover.
-- It is NULL for a bundle with no card. When the photo is deleted the column is cleared, and the day is calculated again
-- (10-2), which gives the bundle a cover again.
ALTER TABLE recommended_bundles
    ADD COLUMN cover_photo_id BIGINT REFERENCES photos(id) ON DELETE SET NULL;
