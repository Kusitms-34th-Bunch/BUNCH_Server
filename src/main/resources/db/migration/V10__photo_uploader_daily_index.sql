-- Daily upload limit: counts one member's uploads since a point in time.
CREATE INDEX ix_photo_uploaders_member_uploaded_at
    ON photo_uploaders(member_id, uploaded_at);
