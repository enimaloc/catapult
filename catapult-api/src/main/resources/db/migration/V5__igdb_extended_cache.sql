-- ============================================================
-- V5 — Cache étendu IGDB : identifiants plateformes + age ratings
-- ============================================================

-- Identifiants plateformes (toutes plateformes, preload)
-- PK (igdb_id, source_id) — 1 entrée par jeu par plateforme
CREATE TABLE igdb_game_external_id (
    igdb_id   VARCHAR(20)  NOT NULL,
    source_id BIGINT       NOT NULL,
    uid       VARCHAR(500) NOT NULL,
    PRIMARY KEY (igdb_id, source_id)
);

-- Index pour lookup inverse : "quel jeu IGDB correspond à ce uid sur cette plateforme ?"
CREATE INDEX idx_ext_id_lookup ON igdb_game_external_id (source_id, uid);

-- Age ratings IGDB (entités propres, id IGDB unique)
CREATE TABLE igdb_age_rating (
    id       BIGINT PRIMARY KEY,
    category INT    NOT NULL,   -- 1=ESRB, 2=PEGI, 3=CERO, 4=USK, 5=GRAC, 6=CLASS_IND, 7=ACB
    rating   INT    NOT NULL    -- valeur enum IGDB (ex: ESRB: E=1, E10=2, T=4, M=8, AO=16)
);

-- Table de liaison n:n jeu ↔ age rating
CREATE TABLE igdb_game_age_rating (
    igdb_id       VARCHAR(20) NOT NULL,
    age_rating_id BIGINT      NOT NULL REFERENCES igdb_age_rating(id) ON DELETE CASCADE,
    PRIMARY KEY (igdb_id, age_rating_id)
);
