-- A member's speedrun.com account: the name they declared and their personal API key (encrypted by
-- TokenEncryptionService, never stored in clear), with the account that key proved. The row exists only while it
-- holds a name or a key; deleting the member's account deletes it. `version` (optimistic locking) keeps a stale write
-- from bringing back a key the member removed meanwhile.
CREATE TABLE speedrun_account (
    user_id           UUID        PRIMARY KEY REFERENCES user_account (id) ON DELETE CASCADE,
    declared_username TEXT,
    api_key_enc       TEXT,
    key_user_id       TEXT,
    key_user_name     TEXT,
    key_checked_at    TIMESTAMPTZ,
    key_invalid       BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at        TIMESTAMPTZ NOT NULL,
    version           BIGINT      NOT NULL DEFAULT 0
);
