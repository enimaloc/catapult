-- ============================================================
-- V12 — Replace igdb_age_rating_category with igdb_rating_descriptor
--        (maps Twitch CCLs to content descriptors, not age-rating levels)
-- ============================================================

-- Drop old mapping and category tables
DROP TABLE IF EXISTS ccl_igdb_mapping;
DROP TABLE IF EXISTS igdb_age_rating_category;

-- IGDB content descriptors (id is IGDB's own stable global ID)
CREATE TABLE igdb_rating_descriptor (
    id              BIGINT  PRIMARY KEY,
    description     VARCHAR NOT NULL,
    organization_id INT,
    display_name    VARCHAR NOT NULL
);

-- Recreated mapping: Twitch CCL → IGDB content descriptor
CREATE TABLE ccl_igdb_mapping (
    twitch_ccl_id             VARCHAR NOT NULL REFERENCES twitch_ccl_definition(id) ON DELETE CASCADE,
    igdb_rating_descriptor_id BIGINT  NOT NULL REFERENCES igdb_rating_descriptor(id) ON DELETE CASCADE,
    PRIMARY KEY (twitch_ccl_id, igdb_rating_descriptor_id)
);
