-- Retain only the notification rows that are still inside the app's 30-day window.
DELETE FROM bunch_join_notifications
WHERE created_at <= CURRENT_TIMESTAMP - INTERVAL '30 days';

ALTER TABLE bunch_join_notifications
    RENAME TO notifications;

ALTER TABLE notifications
    RENAME CONSTRAINT bunch_join_notifications_pkey TO notifications_pkey;

-- Remove the V3-only constraints before changing the legacy type and columns.
ALTER TABLE notifications
    DROP CONSTRAINT uk_join_notification_recipient,
    DROP CONSTRAINT bunch_join_notifications_type_check,
    DROP CONSTRAINT bunch_join_notifications_check,
    DROP CONSTRAINT bunch_join_notifications_bunch_id_fkey,
    DROP CONSTRAINT bunch_join_notifications_joined_membership_id_fkey,
    DROP CONSTRAINT bunch_join_notifications_recipient_member_id_fkey,
    DROP CONSTRAINT bunch_join_notifications_joined_member_id_fkey;

ALTER TABLE notifications
    ALTER COLUMN type TYPE VARCHAR(40),
    ADD COLUMN dedupe_key VARCHAR(200),
    ADD COLUMN title TEXT,
    ADD COLUMN body TEXT,
    ADD COLUMN target JSONB,
    ADD COLUMN activity_data JSONB,
    ADD COLUMN group_until TIMESTAMP WITH TIME ZONE,
    ADD COLUMN read_at TIMESTAMP WITH TIME ZONE;

UPDATE notifications AS notification
SET type = 'MEMBER_JOINED',
    dedupe_key = 'MEMBER_JOINED:' || notification.joined_membership_id::TEXT,
    title = '새 멤버가 들어왔어요',
    body = joined_member.nickname || '님이 번치에 참여했어요.',
    target = jsonb_build_object(
        'kind', 'MEMBERS',
        'bunchId', notification.bunch_id
    )
FROM members AS joined_member
WHERE joined_member.id = notification.joined_member_id;

ALTER TABLE notifications
    DROP COLUMN joined_membership_id,
    DROP COLUMN joined_member_id,
    ALTER COLUMN type DROP DEFAULT,
    ALTER COLUMN dedupe_key SET NOT NULL,
    ALTER COLUMN title SET NOT NULL,
    ALTER COLUMN body SET NOT NULL,
    ALTER COLUMN target SET NOT NULL,
    ADD CONSTRAINT fk_notifications_recipient_member
        FOREIGN KEY (recipient_member_id)
        REFERENCES members(id)
        ON DELETE CASCADE,
    ADD CONSTRAINT fk_notifications_bunch
        FOREIGN KEY (bunch_id)
        REFERENCES bunches(id)
        ON DELETE CASCADE,
    ADD CONSTRAINT ck_notifications_type
        CHECK (type IN (
            'DRAFT_PARTICIPANT_ADDED',
            'DRAFT_ACTIVITY',
            'COMMENT_REMINDER',
            'DRAFT_PARTICIPANT_REMOVED',
            'DRAFT_READY_TO_PUBLISH',
            'DRAFT_PARTICIPANTS_INSUFFICIENT',
            'BUNDLE_PUBLISHED',
            'CANDIDATE_CREATED',
            'CHARACTER_GROWN',
            'MEMBER_JOINED',
            'CO_OWNER_GRANTED',
            'CO_OWNER_REVOKED',
            'OWNER_TRANSFERRED',
            'MEMBER_REMOVED',
            'BUNCH_DELETED',
            'BUNCH_VISIBILITY_CHANGED',
            'BUNCH_FOLLOWED',
            'FOLLOW_REQUESTED',
            'FOLLOW_REQUEST_APPROVED',
            'SOCIAL_COMMENT_CREATED',
            'BUNDLE_LIKED'
        )),
    ADD CONSTRAINT ck_notifications_app_type
        CHECK (type NOT IN ('MEMBER_REMOVED', 'BUNCH_DELETED')),
    ADD CONSTRAINT uk_notifications_recipient_dedupe
        UNIQUE (recipient_member_id, dedupe_key);

CREATE INDEX ix_notifications_recipient_bunch_created_id
    ON notifications(recipient_member_id, bunch_id, created_at DESC, id DESC);
