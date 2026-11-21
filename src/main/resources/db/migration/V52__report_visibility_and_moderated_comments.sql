-- Reports retain their own history but never suppress content, including previously reported targets.
DELETE FROM feed_content_hides WHERE reporter_bunch_id IS NOT NULL;

-- Legacy globally hidden comments become ordinary deleted placeholders, preserving replies and counts.
UPDATE social_comments c
SET content = '', deleted_at = COALESCE(c.deleted_at, GREATEST(c.created_at, now()))
WHERE EXISTS (
    SELECT 1 FROM feed_content_hides hide
    WHERE hide.target_kind = 'COMMENT' AND hide.target_id = c.id
      AND hide.reporter_bunch_id IS NULL
);

UPDATE notifications n
SET body = '삭제된 댓글입니다'
WHERE n.type = 'SOCIAL_COMMENT_CREATED' AND EXISTS (
    SELECT 1 FROM feed_content_hides hide
    WHERE hide.target_kind = 'COMMENT' AND hide.reporter_bunch_id IS NULL
      AND n.target ->> 'commentId' = hide.target_id::text
);

UPDATE push_jobs job
SET payload = jsonb_set(job.payload, '{aps,alert,body}', to_jsonb('삭제된 댓글입니다'::text))
WHERE job.type = 'SOCIAL_COMMENT_CREATED' AND EXISTS (
    SELECT 1 FROM feed_content_hides hide
    WHERE hide.target_kind = 'COMMENT' AND hide.reporter_bunch_id IS NULL
      AND job.payload -> 'target' ->> 'commentId' = hide.target_id::text
);

DELETE FROM feed_content_hides WHERE target_kind = 'COMMENT' AND reporter_bunch_id IS NULL;
