-- The first day of the recommended bundle a draft was made from (photo box 4.1.3, 4.3.11). The recommended bundle is
-- deleted when the draft is made, so the draft keeps the day for its first date chip. A draft that was not made from
-- a recommended bundle has none.
ALTER TABLE bundles ADD COLUMN recommended_date DATE;
