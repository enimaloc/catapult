ALTER TABLE user_account ADD COLUMN is_system BOOLEAN NOT NULL DEFAULT FALSE;
-- Partial unique index: at most one row may have is_system = TRUE
CREATE UNIQUE INDEX idx_unique_system_account ON user_account (is_system) WHERE is_system = TRUE;
