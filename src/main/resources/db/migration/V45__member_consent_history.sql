-- The optional demographics consent (7.4.5) is turned on and off, and every change is kept (1.3.4 rule 1).
-- Until now a row only said "agreed", so the same (member, type, version) could exist once.
ALTER TABLE member_consents
    ADD COLUMN agreed BOOLEAN NOT NULL DEFAULT TRUE;

-- Every row written so far is an agreement, so the default fills them. New rows always say what they mean.
ALTER TABLE member_consents
    ALTER COLUMN agreed DROP DEFAULT;

ALTER TABLE member_consents
    DROP CONSTRAINT uk_member_consent;

-- The required terms are still agreed to once per version, which keeps a repeated signup request from doubling them.
CREATE UNIQUE INDEX uk_member_consent_required
    ON member_consents (member_id, type, version)
    WHERE type <> 'DEMOGRAPHICS';

-- The current state of a consent is its latest row.
CREATE INDEX ix_member_consents_latest
    ON member_consents (member_id, type, id DESC);
