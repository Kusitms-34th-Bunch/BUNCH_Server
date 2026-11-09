-- The 64-bit value that sums up what a photo looks like, so burst shots can be told apart from different pictures
-- (photo box 4.2.8 rule 1). It is calculated when the photo is uploaded. A photo without a row has no value: it was
-- uploaded before this table, or the image could not be read, and it is simply never reduced as a burst shot.
CREATE TABLE photo_image_fingerprints (
    photo_id BIGINT PRIMARY KEY REFERENCES photos(id) ON DELETE CASCADE,
    fingerprint BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
