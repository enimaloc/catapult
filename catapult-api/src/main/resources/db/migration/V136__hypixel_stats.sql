-- Hypixel (lot 4). The Hypixel API keys the admin shares (round robin, like the RetroAchievements ones); while there
-- is none, the Hypixel source stays off. And a member's declared Minecraft account: the UUID (32 hex digits, no
-- dashes) Hypixel knows them by, checked with Hypixel before it is written, and the name it was shown with. No secret
-- is stored with the account. Deleting the member's account deletes the row. `version` (optimistic locking) keeps two
-- settings writes from overwriting each other silently.
CREATE TABLE hypixel_api_key (
    api_key      VARCHAR(255) NOT NULL,
    owner        UUID         NULL REFERENCES user_account (id) ON DELETE CASCADE,
    is_exclusive BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (api_key)
);

CREATE TABLE hypixel_account (
    user_id        UUID        PRIMARY KEY REFERENCES user_account (id) ON DELETE CASCADE,
    minecraft_uuid TEXT        NOT NULL,
    minecraft_name TEXT        NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    version        BIGINT      NOT NULL DEFAULT 0
);
