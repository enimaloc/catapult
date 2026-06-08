CREATE TABLE igdb_game_ccl_cache (
    igdb_id   VARCHAR(20)  NOT NULL PRIMARY KEY,
    ccls      VARCHAR(500) NOT NULL,
    cached_at TIMESTAMP    NOT NULL DEFAULT now()
);
