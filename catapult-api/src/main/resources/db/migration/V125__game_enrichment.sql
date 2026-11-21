-- Shared cache of the game enrichment sources (HLTB, ProtonDB, SteamGridDB, Steam Deck, PCGamingWiki, trailer).
-- A null payload means "known absent": the source was asked and has nothing for this key.
CREATE TABLE game_enrichment_cache (
    source      VARCHAR(32)  NOT NULL,
    lookup_key  VARCHAR(512) NOT NULL,
    payload     JSONB        NULL,
    fetched_at  TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (source, lookup_key)
);

-- Pool of SteamGridDB API keys, same shape as dtdd_api_key.
CREATE TABLE steamgriddb_api_key (
    api_key      VARCHAR(255) NOT NULL,
    owner        UUID         NULL REFERENCES user_account(id) ON DELETE CASCADE,
    is_exclusive BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (api_key)
);
