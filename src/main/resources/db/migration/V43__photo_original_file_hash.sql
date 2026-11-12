-- The SHA-256 the app computes on the original file before it shrinks the photo (photo box 3.2.2 rule 5). It is
-- optional: a photo uploaded without it, and every photo that existed before, is told apart by file_hash alone.
ALTER TABLE photos
    ADD COLUMN original_file_hash VARCHAR(64),
    ADD CONSTRAINT ck_photo_original_file_hash_sha256
        CHECK (original_file_hash IS NULL OR original_file_hash ~ '^[0-9a-f]{64}$');

-- One photo per original in a bunch. Photos without the value are not compared.
CREATE UNIQUE INDEX uk_photo_bunch_original_file_hash
    ON photos (bunch_id, original_file_hash)
    WHERE original_file_hash IS NOT NULL;
