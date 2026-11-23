-- Run once before the old application is frozen. This is intentionally additive and nullable: the old
-- application continues to read public_id/url while the copier fills object_key.
ALTER TABLE image_assets
    ADD COLUMN IF NOT EXISTS object_key TEXT;
