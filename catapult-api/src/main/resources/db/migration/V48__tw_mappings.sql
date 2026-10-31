CREATE TABLE tw_dtdd_topic_mapping (
    id              BIGSERIAL PRIMARY KEY,
    tw_id           VARCHAR(40) NOT NULL REFERENCES tw_definition(id) ON DELETE CASCADE,
    dtdd_topic_name VARCHAR(200) NOT NULL,
    UNIQUE (tw_id, dtdd_topic_name)
);
CREATE INDEX idx_tw_dtdd_topic_name ON tw_dtdd_topic_mapping (LOWER(dtdd_topic_name));

CREATE TABLE tw_igdb_descriptor_mapping (
    id            BIGSERIAL PRIMARY KEY,
    tw_id         VARCHAR(40) NOT NULL REFERENCES tw_definition(id) ON DELETE CASCADE,
    descriptor_id BIGINT      NOT NULL REFERENCES igdb_rating_descriptor(id) ON DELETE CASCADE,
    UNIQUE (tw_id, descriptor_id)
);
CREATE INDEX idx_tw_igdb_descriptor_id ON tw_igdb_descriptor_mapping (descriptor_id);

CREATE TABLE tw_steam_content_id_mapping (
    id               BIGSERIAL PRIMARY KEY,
    tw_id            VARCHAR(40) NOT NULL REFERENCES tw_definition(id) ON DELETE CASCADE,
    steam_content_id INT         NOT NULL CHECK (steam_content_id BETWEEN 1 AND 5),
    UNIQUE (tw_id, steam_content_id)
);

CREATE TABLE tw_steam_keyword (
    id      BIGSERIAL PRIMARY KEY,
    tw_id   VARCHAR(40) NOT NULL REFERENCES tw_definition(id) ON DELETE CASCADE,
    keyword VARCHAR(50) NOT NULL,
    UNIQUE (tw_id, keyword)
);
