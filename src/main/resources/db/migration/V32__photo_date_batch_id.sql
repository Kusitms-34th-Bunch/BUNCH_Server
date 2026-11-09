-- The photos that were given a date in one request are one batch (photo box 3.4.1, 4.2.7 rule 3). The recommended
-- bundles use it as another sign that photos belong together, next to the upload batch of photo_uploaders. A photo has
-- none until a date is set for it, and loses it when the date is cleared. Photos that were dated before this column
-- stay without one.
ALTER TABLE photos ADD COLUMN date_batch_id UUID;

ALTER TABLE photos ADD CONSTRAINT ck_photo_date_batch
    CHECK (date_batch_id IS NULL OR date_source = 'USER');
