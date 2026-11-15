-- One latest placement per moved photo, retained as a minimal tombstone on removal.
-- Photo/neighbor IDs deliberately have no FK: physical photo removal must preserve them.
CREATE TABLE bundle_photo_order_marks (
    bundle_photo_id BIGINT PRIMARY KEY,
    bundle_id BIGINT NOT NULL REFERENCES bundles(id) ON DELETE CASCADE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    previous_id BIGINT,
    next_id BIGINT,
    slot INTEGER NOT NULL,
    move_sequence BIGINT,
    CONSTRAINT ck_bundle_photo_order_slot CHECK (slot >= 0),
    CONSTRAINT ck_bundle_photo_order_neighbors CHECK (
        (previous_id IS NULL OR previous_id <> bundle_photo_id)
        AND (next_id IS NULL OR next_id <> bundle_photo_id)
        AND (previous_id IS NULL OR next_id IS NULL OR previous_id <> next_id)
    )
);

CREATE INDEX ix_bundle_photo_order_marks_bundle ON bundle_photo_order_marks(bundle_id);
