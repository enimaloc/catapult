-- ============================================================
-- V4 — Cache persistant des résolutions IGDB
-- lookup_key : "steam:<uid>" ou "name:<normalized_name>"
-- ============================================================
CREATE TABLE igdb_game_cache (
    lookup_key VARCHAR(600) PRIMARY KEY,
    igdb_id    VARCHAR(20)  NOT NULL,
    name       VARCHAR(500) NOT NULL,
    cached_at  TIMESTAMP    NOT NULL DEFAULT now()
);
