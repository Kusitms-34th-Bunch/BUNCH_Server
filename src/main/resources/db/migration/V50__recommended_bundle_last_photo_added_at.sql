-- When the last photo of a recommended bundle was added to the photo box, which is the latest photos.created_at of the
-- photos of the bundle. A recommended bundle is no longer shown 30 days after the day this is (photo box specification
-- 4.2.9 rule 4), so it is kept with the bundle and the bundles that are past it can be deleted by one statement.
-- The bundles that are already there (none are kept in production, the new recommended bundles are switched off) are
-- given the time they were made, which is not later than any photo they could have.
ALTER TABLE recommended_bundles
    ADD COLUMN last_photo_added_at TIMESTAMP WITH TIME ZONE;

UPDATE recommended_bundles SET last_photo_added_at = created_at;

ALTER TABLE recommended_bundles
    ALTER COLUMN last_photo_added_at SET NOT NULL;

CREATE INDEX ix_recommended_bundles_last_photo_added_at
    ON recommended_bundles(last_photo_added_at);
