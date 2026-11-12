ALTER TABLE bundle_locations ALTER COLUMN address DROP NOT NULL;
ALTER TABLE pinned_places ALTER COLUMN address DROP NOT NULL;
ALTER TABLE feed_advertisements ALTER COLUMN place_address DROP NOT NULL;
