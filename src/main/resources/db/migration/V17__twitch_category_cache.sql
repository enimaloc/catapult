-- ============================================================
-- V17 — Twitch category cache : cache local des catégories Twitch
-- ============================================================

CREATE TABLE twitch_category_cache (
    id          VARCHAR(32)  NOT NULL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    box_art_url VARCHAR(512),
    igdb_id     VARCHAR(32),
    cached_at   TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_twitch_category_name      ON twitch_category_cache (lower(name));
CREATE INDEX idx_twitch_category_cached_at ON twitch_category_cache (cached_at);
