-- Complete the nullable deep-link fields for existing JSON payloads without creating new alerts or jobs.
UPDATE notifications AS notification
SET target = jsonb_set(
    notification.target,
    '{parentCommentId}',
    COALESCE(
        (
            SELECT to_jsonb(comment.parent_id)
            FROM social_comments AS comment
            WHERE notification.target ->> 'kind' = 'BUNDLE_COMMENTS'
              AND comment.bundle_id::text = notification.target ->> 'bundleId'
              AND comment.id::text = notification.target ->> 'commentId'
        ),
        'null'::jsonb
    ),
    true
);

-- Only jobs that have not been sent may receive the new wire fields. Text comparisons avoid
-- casting arbitrary legacy JSON values to bigint, so malformed or absent IDs resolve to null.
UPDATE push_jobs AS job
SET payload = jsonb_set(
    jsonb_set(
        job.payload,
        '{target,parentCommentId}',
        COALESCE(
            (
                SELECT to_jsonb(comment.parent_id)
                FROM social_comments AS comment
                WHERE job.payload -> 'target' ->> 'kind' = 'BUNDLE_COMMENTS'
                  AND comment.bundle_id::text = job.payload -> 'target' ->> 'bundleId'
                  AND comment.id::text = job.payload -> 'target' ->> 'commentId'
            ),
            'null'::jsonb
        ),
        true
    ),
    '{notificationId}',
    COALESCE(to_jsonb(job.notification_id), 'null'::jsonb),
    true
)
WHERE job.status = 'PENDING';
