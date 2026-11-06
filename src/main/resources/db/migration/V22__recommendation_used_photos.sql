-- The photos of a recommended bundle a draft was made from (photo box 4.1.3 rule 5, 4.2.9 rule 5). The recommended
-- bundle is deleted when it is used, and it is not recommended again: the photos that were not used by the draft only
-- become a new recommended bundle when a photo that is not in this table is added to them. A photo is deleted from
-- here with the photo, and its row is deleted when the draft that took the photos is deleted.
CREATE TABLE recommendation_used_photos (
    photo_id BIGINT PRIMARY KEY REFERENCES photos(id) ON DELETE CASCADE,
    bunch_id BIGINT NOT NULL REFERENCES bunches(id) ON DELETE CASCADE
);

CREATE INDEX ix_recommendation_used_photos_bunch_id
    ON recommendation_used_photos(bunch_id);
