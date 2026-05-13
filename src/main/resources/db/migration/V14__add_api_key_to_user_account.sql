ALTER TABLE user_account
    ADD COLUMN api_key VARCHAR(64) UNIQUE;

CREATE INDEX idx_user_account_api_key ON user_account(api_key);
