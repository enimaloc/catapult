CREATE TABLE dtdd_search_cache (
    query_normalized VARCHAR(256) PRIMARY KEY,
    dtdd_ids         JSONB        NOT NULL,
    searched_at      TIMESTAMPTZ  NOT NULL
);
