-- An admin's choice of Twitch category for a detected game (platform + its ID), applied to every streamer's
-- binding of that game except the MANUAL ones, which then get the MAPPED status.
CREATE TABLE game_mapping (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    source_type       VARCHAR      NOT NULL,
    source_id         VARCHAR      NOT NULL,
    twitch_game_id    VARCHAR      NOT NULL,
    twitch_game_name  VARCHAR      NOT NULL,
    -- false: the CCLs/TWs come from IGDB, as without a mapping; true: the admin's (game_mapping_ccl/_tw), even none.
    ccls_overridden   BOOLEAN      NOT NULL DEFAULT FALSE,
    tws_overridden    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT now(),
    updated_by        UUID         REFERENCES user_account (id) ON DELETE SET NULL,
    CONSTRAINT uq_game_mapping_source UNIQUE (source_type, source_id)
);

CREATE TABLE game_mapping_ccl (
    mapping_id UUID    NOT NULL REFERENCES game_mapping (id) ON DELETE CASCADE,
    ccl        VARCHAR NOT NULL,
    PRIMARY KEY (mapping_id, ccl)
);

CREATE TABLE game_mapping_tw (
    mapping_id UUID    NOT NULL REFERENCES game_mapping (id) ON DELETE CASCADE,
    tw         VARCHAR NOT NULL,
    PRIMARY KEY (mapping_id, tw)
);

-- Finds the bindings that follow a mapping (game_binding.status has no CHECK constraint: MAPPED needs no change there).
CREATE INDEX idx_binding_source_status ON game_binding (source_type, source_id, status);
