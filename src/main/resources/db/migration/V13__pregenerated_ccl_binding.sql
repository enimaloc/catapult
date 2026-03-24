-- ============================================================
-- V13 — Placeholder (pre-generated seed moved to AdminCclService)
--
-- Inserting ccl_igdb_mapping rows at migration time is impossible
-- because igdb_rating_descriptor is populated by AdminCclService
-- (IGDB API call) which runs after Flyway. Default mappings are
-- now seeded by AdminCclService.applyDefaultMappings() on first
-- startup using keyword matching on synced descriptor descriptions.
-- ============================================================
SELECT 1;
