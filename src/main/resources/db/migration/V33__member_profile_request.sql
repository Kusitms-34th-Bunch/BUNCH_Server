-- The latest profile edit request of a member, so a retried request is applied once (7.2.7).
-- It is kept apart from registration_fingerprint, which the sign up replay compares.
ALTER TABLE members
    ADD COLUMN profile_request_key UUID,
    ADD COLUMN profile_request_fingerprint VARCHAR(64),
    ADD CONSTRAINT ck_member_profile_request CHECK (
        (profile_request_key IS NULL) = (profile_request_fingerprint IS NULL)
    );
