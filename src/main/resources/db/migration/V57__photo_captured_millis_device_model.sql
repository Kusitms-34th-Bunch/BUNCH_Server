-- What the app tells about the moment and the device of a photo when it uploads it (recommended bundle combination
-- specification, F7 and R1). Both values are optional: a photo uploaded before this migration, or by an app version
-- that does not send them, has no value. A missing value is not a guess at "0 milliseconds" or "a known device";
-- whoever reads it decides.
--
-- The size of the stored image (width and height) is not added here: V56__photo_dimensions.sql adds it.

-- The milliseconds of the capture time, 0 to 999. The capture time itself (captured_time) stays in seconds, as the app
-- sends it. A photo without a capture time has no milliseconds.
ALTER TABLE photos ADD COLUMN captured_millis INTEGER;
ALTER TABLE photos ADD CONSTRAINT ck_photo_captured_millis
    CHECK (captured_millis IS NULL OR (captured_millis BETWEEN 0 AND 999 AND captured_time IS NOT NULL));

-- The model of the device that took the photo, as the app sends it. Only a photo taken with a camera has one.
ALTER TABLE photo_reco_feature ADD COLUMN device_model VARCHAR(64);
