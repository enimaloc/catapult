-- ============================================================
-- V11 — Admin CCL management
-- ============================================================

-- Twitch CCL definitions (fetched from Twitch API at startup)
CREATE TABLE twitch_ccl_definition (
    id          VARCHAR PRIMARY KEY,
    name        VARCHAR,
    description TEXT
);

-- IGDB age rating global catalogue (fetched from IGDB API at startup)
CREATE TABLE igdb_age_rating_category (
    id           BIGSERIAL PRIMARY KEY,
    category_id  INT     NOT NULL,
    rating       INT     NOT NULL,
    display_name VARCHAR NOT NULL,
    UNIQUE (category_id, rating)
);

-- Admin-configured mapping: IGDB age rating → Twitch CCL
CREATE TABLE ccl_igdb_mapping (
    twitch_ccl_id               VARCHAR NOT NULL REFERENCES twitch_ccl_definition(id) ON DELETE CASCADE,
    igdb_age_rating_category_id BIGINT  NOT NULL REFERENCES igdb_age_rating_category(id) ON DELETE CASCADE,
    PRIMARY KEY (twitch_ccl_id, igdb_age_rating_category_id)
);
