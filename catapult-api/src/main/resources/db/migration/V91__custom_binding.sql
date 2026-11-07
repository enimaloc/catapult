-- Bindings a streamer creates and triggers by hand, tied to no game (« Art », « Développement »…),
-- behind the custom.bindings experiment. At most one is active per streamer (custom_binding_activation).
CREATE TABLE custom_binding (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID         NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    name             VARCHAR(40)  NOT NULL,
    twitch_game_id   VARCHAR      NOT NULL,
    twitch_game_name VARCHAR      NOT NULL,
    uninterruptible  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_custom_binding_user_name ON custom_binding (user_id, LOWER(name));

CREATE TABLE custom_binding_ccl (
    custom_binding_id UUID    NOT NULL REFERENCES custom_binding (id) ON DELETE CASCADE,
    ccl               VARCHAR NOT NULL,
    PRIMARY KEY (custom_binding_id, ccl)
);

CREATE TABLE custom_binding_tw (
    custom_binding_id UUID        NOT NULL REFERENCES custom_binding (id) ON DELETE CASCADE,
    tw                VARCHAR(40) NOT NULL,
    PRIMARY KEY (custom_binding_id, tw)
);

-- The streamer's active custom binding, if any, and when it was triggered: survives a restart.
CREATE TABLE custom_binding_activation (
    user_id           UUID      PRIMARY KEY REFERENCES user_account (id) ON DELETE CASCADE,
    custom_binding_id UUID      NOT NULL REFERENCES custom_binding (id) ON DELETE CASCADE,
    activated_at      TIMESTAMP NOT NULL
);
