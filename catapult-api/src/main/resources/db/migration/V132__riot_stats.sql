-- Riot Games (lot 4). The Riot API keys the admin shares (round robin, like the RetroAchievements ones); a key may be
-- kept to one routing cluster (`region_scope`: americas, asia, europe or sea; null for every cluster). And a
-- member's declared Riot ID: the name and tag they gave, the PUUID Riot proved for it (stable when the name
-- changes), the League of Legends platform they play on and its routing cluster. No secret is stored with the
-- account. Deleting the member's account deletes the row. `version` (optimistic locking) keeps two settings writes
-- from overwriting each other silently.
CREATE TABLE riot_api_key (
    api_key      VARCHAR(255) NOT NULL,
    region_scope TEXT         NULL,
    owner        UUID         NULL REFERENCES user_account (id) ON DELETE CASCADE,
    is_exclusive BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (api_key)
);

CREATE TABLE riot_account (
    user_id    UUID        PRIMARY KEY REFERENCES user_account (id) ON DELETE CASCADE,
    game_name  TEXT        NOT NULL,
    tag_line   TEXT        NOT NULL,
    puuid      TEXT        NOT NULL,
    platform   TEXT        NOT NULL,
    region     TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version    BIGINT      NOT NULL DEFAULT 0
);
