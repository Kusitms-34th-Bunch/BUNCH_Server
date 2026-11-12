-- Historical publishers are unknown; never infer them from created_by.
ALTER TABLE bundles ADD COLUMN published_by BIGINT REFERENCES members(id) ON DELETE SET NULL;
