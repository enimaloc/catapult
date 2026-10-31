CREATE TABLE dtdd_topics_cache (
    dtdd_id        BIGINT      PRIMARY KEY REFERENCES dtdd_game_cache(dtdd_id) ON DELETE CASCADE,
    yes_topics     JSONB       NOT NULL DEFAULT '[]'::jsonb,
    no_topics      JSONB       NOT NULL DEFAULT '[]'::jsonb,
    mostly_topics  JSONB       NOT NULL DEFAULT '[]'::jsonb,
    fetched_at     TIMESTAMPTZ NOT NULL
);
