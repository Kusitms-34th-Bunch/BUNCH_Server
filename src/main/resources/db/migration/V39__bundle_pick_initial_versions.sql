ALTER TABLE bundle_participants
    ADD COLUMN pick_initial_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_bundle_participant_pick_initial_version_nonnegative
        CHECK (pick_initial_version >= 0);
