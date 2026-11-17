-- What the app tells about a photo file when it uploads it (recommended bundle combination, 3-6, 4-4, 4-5, 5-2).
-- Both values are optional: a photo uploaded before this migration, or by an app version that does not send them,
-- has no value. A missing value is not a guess at "camera" or "not camera"; whoever reads it decides.

-- The UTC offset of the place where the photo was taken, as the app sends it (+09:00). The capture time of the photo
-- is the local time. The range -12:00 to +14:00 is checked by the server, the database checks the shape only.
ALTER TABLE photos ADD COLUMN captured_utc_offset VARCHAR(6);
ALTER TABLE photos ADD CONSTRAINT ck_photo_captured_utc_offset
    CHECK (captured_utc_offset IS NULL OR captured_utc_offset ~ '^[+-][0-9]{2}:[0-5][0-9]$');

-- The features of a photo that the recommendation uses, one row per photo (4-5). Only the original type exists
-- for now; the columns of the AI analysis and the quality come with the issues that calculate them. A photo without a
-- row was uploaded before this table.
CREATE TABLE photo_reco_feature (
    photo_id BIGINT PRIMARY KEY REFERENCES photos(id) ON DELETE CASCADE,
    source VARCHAR(16),
    CONSTRAINT ck_photo_reco_feature_source CHECK (source IS NULL OR source IN ('CAMERA', 'SCREENSHOT', 'SAVED'))
);
