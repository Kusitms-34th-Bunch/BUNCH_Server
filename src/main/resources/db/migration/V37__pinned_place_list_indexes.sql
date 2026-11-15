CREATE INDEX ix_pinned_place_recent
    ON pinned_places(bunch_id, pinned_at DESC, id DESC);

CREATE INDEX ix_pinned_place_member_recent
    ON pinned_places(bunch_id, pinned_by_member_id, pinned_at DESC, id DESC);
