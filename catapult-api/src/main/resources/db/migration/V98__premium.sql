ALTER TABLE user_account ADD COLUMN premium BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE user_account ADD COLUMN premium_until DATE;

-- Existing accounts keep the premium features they had before premium existed.
UPDATE user_account SET premium = TRUE;
