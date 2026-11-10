-- Push settings are four categories per account (my page 7.3.3) instead of one toggle per notification type.
-- A category that has no entry is on. The old per-type column is kept as it is and is no longer read.
ALTER TABLE push_preferences
    ADD COLUMN categories JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD CONSTRAINT ck_push_preferences_categories_object
        CHECK (jsonb_typeof(categories) = 'object');

-- A category is off when any notification type in it was switched off before.
UPDATE push_preferences
SET categories = jsonb_strip_nulls(jsonb_build_object(
    'BUNDLE_NEWS', CASE WHEN
        types ->> 'DRAFT_PARTICIPANT_ADDED' = 'false' OR
        types ->> 'DRAFT_ACTIVITY' = 'false' OR
        types ->> 'COMMENT_REMINDER' = 'false' OR
        types ->> 'DRAFT_PARTICIPANT_REMOVED' = 'false' OR
        types ->> 'DRAFT_READY_TO_PUBLISH' = 'false' OR
        types ->> 'DRAFT_PARTICIPANTS_INSUFFICIENT' = 'false' OR
        types ->> 'BUNDLE_PUBLISHED' = 'false' OR
        types ->> 'CANDIDATE_CREATED' = 'false'
        THEN FALSE END,
    'MEMBER_ROLE', CASE WHEN
        types ->> 'MEMBER_JOINED' = 'false' OR
        types ->> 'CO_OWNER_GRANTED' = 'false' OR
        types ->> 'CO_OWNER_REVOKED' = 'false' OR
        types ->> 'OWNER_TRANSFERRED' = 'false' OR
        types ->> 'BUNCH_VISIBILITY_CHANGED' = 'false'
        THEN FALSE END,
    'FOLLOW', CASE WHEN
        types ->> 'BUNCH_FOLLOWED' = 'false' OR
        types ->> 'FOLLOW_REQUESTED' = 'false' OR
        types ->> 'FOLLOW_REQUEST_APPROVED' = 'false'
        THEN FALSE END,
    'REACTION', CASE WHEN
        types ->> 'SOCIAL_COMMENT_CREATED' = 'false' OR
        types ->> 'BUNDLE_LIKED' = 'false'
        THEN FALSE END
));

-- When every category is off the app push toggle is off too (7.3.3).
UPDATE push_preferences
SET enabled = FALSE
WHERE categories @> '{"BUNDLE_NEWS": false, "MEMBER_ROLE": false, "FOLLOW": false, "REACTION": false}'::jsonb;
