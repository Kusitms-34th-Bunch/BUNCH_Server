ALTER TABLE bunch_profile_reports ADD COLUMN window_started_at TIMESTAMPTZ;

UPDATE bunch_profile_reports SET window_started_at = created_at;

ALTER TABLE bunch_profile_reports ALTER COLUMN window_started_at SET NOT NULL;
ALTER TABLE bunch_profile_reports DROP CONSTRAINT uk_bunch_report_pair;

CREATE INDEX ix_bunch_profile_report_window
    ON bunch_profile_reports (reporter_bunch_id, target_bunch_id, window_started_at DESC, id DESC);
