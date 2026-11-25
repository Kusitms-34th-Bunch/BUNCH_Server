-- R2 cutover. The one-time copier must populate every image_assets.object_key before this migration runs.
-- This migration intentionally has no manifest-table dependency: the database is the final source of truth.

ALTER TABLE image_assets
    ADD COLUMN IF NOT EXISTS object_key TEXT;

-- The application must be frozen while this guard and the legacy-column removal are committed together.
LOCK TABLE image_assets, members, bunches, photos, bundle_photos
    IN SHARE ROW EXCLUSIVE MODE;

DO $$
BEGIN
    -- This includes referenced and unreferenced rows. An unreferenced asset is still required to have an
    -- archive object key so that the cutover cannot silently lose a legacy original.
    IF EXISTS (
        SELECT 1
        FROM image_assets
        WHERE object_key IS NULL OR btrim(object_key) = ''
    ) THEN
        RAISE EXCEPTION
            'R2 cutover refused: every image_assets row needs a non-blank object_key';
    END IF;

    -- Referenced keys are the only keys the application can sign. Archive keys are deliberately a separate,
    -- deterministic namespace for unreferenced originals; arbitrary URLs and path-like values are not accepted.
    IF EXISTS (
        SELECT 1
        FROM image_assets AS asset
        WHERE asset.object_key !~ '^(photos/[A-Za-z0-9_-]+/original[.]jpg|profiles/[A-Za-z0-9_-]+/profile[.]jpg|archive/(assets/[0-9]+/[a-f0-9]{64}|orphans/[a-f0-9]{64})/original[.][A-Za-z0-9_-]{1,16})$'
    ) THEN
        RAISE EXCEPTION
            'R2 cutover refused: image_assets.object_key is not a canonical image or archive key';
    END IF;

    -- These checks make the referenced-asset guard explicit even though the restrictive FKs already protect
    -- the row identity. They also document that a nullable profile FK does not exempt a non-null reference.
    IF EXISTS (
        SELECT 1
        FROM members AS owner
        LEFT JOIN image_assets AS asset ON asset.id = owner.profile_image_asset_id
        WHERE owner.profile_image_asset_id IS NOT NULL
          AND (asset.id IS NULL OR asset.object_key IS NULL OR btrim(asset.object_key) = '')
    ) OR EXISTS (
        SELECT 1
        FROM bunches AS bunch
        LEFT JOIN image_assets AS asset ON asset.id = bunch.profile_image_asset_id
        WHERE bunch.profile_image_asset_id IS NOT NULL
          AND (asset.id IS NULL OR asset.object_key IS NULL OR btrim(asset.object_key) = '')
    ) OR EXISTS (
        SELECT 1
        FROM photos AS photo
        LEFT JOIN image_assets AS asset ON asset.id = photo.image_asset_id
        WHERE asset.id IS NULL OR asset.object_key IS NULL OR btrim(asset.object_key) = ''
    ) OR EXISTS (
        SELECT 1
        FROM bundle_photos AS bundle_photo
        LEFT JOIN image_assets AS asset ON asset.id = bundle_photo.image_asset_id
        WHERE asset.id IS NULL OR asset.object_key IS NULL OR btrim(asset.object_key) = ''
    ) THEN
        RAISE EXCEPTION
            'R2 cutover refused: a referenced image asset has no verified object_key';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM image_assets AS asset
        WHERE asset.object_key !~ '^photos/[A-Za-z0-9_-]+/original[.]jpg$'
          AND (EXISTS (SELECT 1 FROM photos WHERE image_asset_id = asset.id)
               OR EXISTS (SELECT 1 FROM bundle_photos WHERE image_asset_id = asset.id))
    ) OR EXISTS (
        SELECT 1
        FROM image_assets AS asset
        WHERE asset.object_key !~ '^(photos/[A-Za-z0-9_-]+/original|profiles/[A-Za-z0-9_-]+/profile)[.]jpg$'
          AND (EXISTS (SELECT 1 FROM members WHERE profile_image_asset_id = asset.id)
               OR EXISTS (SELECT 1 FROM bunches WHERE profile_image_asset_id = asset.id))
    ) THEN
        RAISE EXCEPTION
            'R2 cutover refused: referenced photo/profile assets must use their canonical key namespace';
    END IF;
END
$$;

ALTER TABLE image_assets
    ALTER COLUMN object_key SET NOT NULL;

ALTER TABLE image_assets
    DROP CONSTRAINT IF EXISTS uk_image_asset_public_id;

ALTER TABLE image_assets
    ADD CONSTRAINT uk_image_asset_object_key UNIQUE (object_key);

ALTER TABLE image_assets
    DROP COLUMN IF EXISTS public_id,
    DROP COLUMN IF EXISTS url;
