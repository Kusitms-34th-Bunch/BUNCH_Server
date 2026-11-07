-- Keyset traversal must seek by publication order before evaluating per-viewer access predicates.
CREATE INDEX ix_bundles_feed_published_cursor
    ON bundles (published_at DESC, id DESC)
    WHERE status = 'PUBLISHED' AND visibility = 'PUBLIC';
