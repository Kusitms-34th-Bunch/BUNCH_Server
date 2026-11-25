ALTER TABLE photos
    ADD COLUMN width integer,
    ADD COLUMN height integer,
    ADD CONSTRAINT ck_photos_dimensions CHECK (
        (width IS NULL AND height IS NULL)
        OR (width IS NOT NULL AND height IS NOT NULL AND width > 0 AND height > 0)
    );
