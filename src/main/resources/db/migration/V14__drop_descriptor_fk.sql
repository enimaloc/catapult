-- ============================================================
-- V14 — Remove FK on ccl_igdb_mapping.igdb_rating_descriptor_id
--
-- igdb_rating_descriptor is populated at runtime by AdminCclService
-- (IGDB API call), not by a migration. A FK constraint cannot be
-- satisfied when Flyway runs V13 pre-generated seed data before the
-- Spring context starts. The JPA @ManyToMany handles integrity at
-- the application level.
-- ============================================================
ALTER TABLE ccl_igdb_mapping
    DROP CONSTRAINT IF EXISTS ccl_igdb_mapping_igdb_rating_descriptor_id_fkey;
