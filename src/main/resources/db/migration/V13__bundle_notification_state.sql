ALTER TABLE bundles
    ADD COLUMN ready_notified_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE bundle_participants
    ADD COLUMN reminder24_notified_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN reminder72_notified_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN reminders_cancelled_at TIMESTAMP WITH TIME ZONE;

-- Match Kotlin's blank-comment whitespace rule for legacy participant rows.
UPDATE bundle_participants AS participant
SET reminders_cancelled_at = CURRENT_TIMESTAMP
FROM bundle_comments AS comment
WHERE comment.bundle_id = participant.bundle_id
    AND comment.member_id = participant.member_id
    AND btrim(
        comment.content,
        U&'\0009\000A\000B\000C\000D\001C\001D\001E\001F\0020\00A0\1680'
        || U&'\2000\2001\2002\2003\2004\2005\2006\2007\2008\2009\200A'
        || U&'\2028\2029\202F\205F\3000'
    ) <> '';

CREATE INDEX ix_bundle_participants_reminder_due
    ON bundle_participants (joined_at, bundle_id, id)
    WHERE reminders_cancelled_at IS NULL;
