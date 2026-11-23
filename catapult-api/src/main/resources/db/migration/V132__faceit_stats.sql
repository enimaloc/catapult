-- FACEIT (lot 4). The FACEIT Data API server keys the admin shares (round robin, like the RetroAchievements ones),
-- and a member's declared FACEIT nickname with the player id FACEIT proved for it (stable when the nickname
-- changes). No secret is stored with the account. Deleting the member's account deletes the row. `version`
-- (optimistic locking) keeps two settings writes from overwriting each other silently.
CREATE TABLE faceit_api_key (
    api_key      VARCHAR(255) NOT NULL,
    owner        UUID         NULL REFERENCES user_account (id) ON DELETE CASCADE,
    is_exclusive BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (api_key)
);

CREATE TABLE faceit_account (
    user_id    UUID        PRIMARY KEY REFERENCES user_account (id) ON DELETE CASCADE,
    nickname   TEXT        NOT NULL,
    player_id  TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version    BIGINT      NOT NULL DEFAULT 0
);
