-- NULL means that the row predates the profanity check and has not been examined yet.
-- Rechecking is intentionally implemented in Kotlin so the immutable dictionary's
-- normalization rules remain the single source of truth.
ALTER TABLE bundles
    ADD COLUMN profanity_dictionary_version VARCHAR(64),
    ADD COLUMN title_has_prohibited_word BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE bundle_comments
    ADD COLUMN has_prohibited_word BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX ix_bundles_draft_profanity_recheck
    ON bundles (id, profanity_dictionary_version)
    WHERE status = 'DRAFT';
