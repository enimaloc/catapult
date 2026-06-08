-- ============================================================
-- V18 — Replace functional name index with trigram GIN index
--       for efficient ILIKE '%query%' autocomplete searches
-- ============================================================

DROP INDEX IF EXISTS idx_twitch_category_name;

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_twitch_category_name ON twitch_category_cache USING gin (name gin_trgm_ops);
