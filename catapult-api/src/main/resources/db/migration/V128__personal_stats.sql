-- The personal stats sources (lot 3). The RetroAchievements API keys the admin shares (round robin, like the
-- IsThereAnyDeal ones), and a member's RetroAchievements account: the name they declared with the ULID RA proved
-- for it, and their personal API key (encrypted by TokenEncryptionService, never stored in clear). Deleting the
-- member's account deletes the row. `version` (optimistic locking) keeps a stale write from bringing back a key
-- the member removed meanwhile.
CREATE TABLE retroachievements_api_key (
    api_key      VARCHAR(255) NOT NULL,
    owner        UUID         NULL REFERENCES user_account(id) ON DELETE CASCADE,
    is_exclusive BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (api_key)
);

CREATE TABLE retroachievements_account (
    user_id     UUID        PRIMARY KEY REFERENCES user_account (id) ON DELETE CASCADE,
    username    TEXT        NOT NULL,
    user_ulid   TEXT        NOT NULL,
    api_key_enc TEXT,
    key_invalid BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at  TIMESTAMPTZ NOT NULL,
    version     BIGINT      NOT NULL DEFAULT 0
);
