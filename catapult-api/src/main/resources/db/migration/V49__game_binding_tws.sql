ALTER TABLE game_binding ADD COLUMN tw_enabled  BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE game_binding ADD COLUMN tw_override BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE binding_tw (
    binding_id UUID         NOT NULL REFERENCES game_binding(id) ON DELETE CASCADE,
    tw         VARCHAR(40)  NOT NULL,
    PRIMARY KEY (binding_id, tw)
);

ALTER TABLE igdb_game_ccl_cache ADD COLUMN descriptor_ids TEXT NULL;
