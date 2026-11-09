ALTER TABLE members ADD COLUMN dev_account VARCHAR(32);
ALTER TABLE members ADD CONSTRAINT uk_member_dev_account UNIQUE (dev_account);
