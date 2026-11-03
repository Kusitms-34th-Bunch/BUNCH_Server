-- Keep the preflight check and schema changes on one stable membership snapshot.
LOCK TABLE bunches, bunch_memberships IN SHARE ROW EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (
        SELECT b.id
        FROM bunches b
        LEFT JOIN bunch_memberships bm ON bm.bunch_id = b.id
        GROUP BY b.id
        HAVING count(*) FILTER (WHERE bm.status = 'ACTIVE') = 0
            OR count(*) FILTER (WHERE bm.status = 'ACTIVE' AND bm.role = 'OWNER') <> 1
    ) THEN
        RAISE EXCEPTION 'Bunch role migration requires exactly one active OWNER and at least one active membership per bunch';
    END IF;
END $$;

ALTER TABLE bunches
    ADD COLUMN profile_version BIGINT NOT NULL DEFAULT 0 CHECK (profile_version >= 0),
    ADD COLUMN membership_version BIGINT NOT NULL DEFAULT 0 CHECK (membership_version >= 0);

ALTER TABLE bunch_memberships
    DROP CONSTRAINT bunch_memberships_role_check,
    ADD CONSTRAINT bunch_memberships_role_check CHECK (role IN ('OWNER', 'CO_OWNER', 'MEMBER')),
    ADD COLUMN ended_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN role_granted_by BIGINT REFERENCES members(id) ON DELETE SET NULL,
    ADD COLUMN last_activity_at TIMESTAMP WITH TIME ZONE;

-- Historical end times cannot be recovered; last-used is not evidence of activity.
UPDATE bunch_memberships
SET ended_at = CURRENT_TIMESTAMP
WHERE status IN ('LEFT', 'REMOVED');

CREATE UNIQUE INDEX uk_bunch_active_owner
    ON bunch_memberships (bunch_id)
    WHERE status = 'ACTIVE' AND role = 'OWNER';
