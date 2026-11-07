CREATE TABLE dtdd_game_cache (
    dtdd_id     BIGINT       PRIMARY KEY,
    name        VARCHAR(512) NOT NULL,
    slug        VARCHAR(512),
    media_type  VARCHAR(64),
    poster_url  VARCHAR(1024),
    fetched_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_dtdd_game_cache_name_lower ON dtdd_game_cache (LOWER(name));
