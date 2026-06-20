CREATE TABLE dtdd_game_mapping (
    igdb_id     VARCHAR(64)  PRIMARY KEY,
    dtdd_id     BIGINT       NULL REFERENCES dtdd_game_cache(dtdd_id) ON DELETE SET NULL,
    confidence  DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    verified    BOOLEAN      NOT NULL DEFAULT FALSE,
    resolved_at TIMESTAMPTZ  NOT NULL
);
