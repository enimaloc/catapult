-- Twitch extension layout of a binding configuration: one row per slot it sets. A NULL extension_id empties the
-- slot; a slot without a row is left as it is.
CREATE TABLE binding_extension_slot (
    binding_id     UUID        NOT NULL REFERENCES game_binding (id) ON DELETE CASCADE,
    slot_type      VARCHAR(16) NOT NULL,
    slot_number    INT         NOT NULL,
    extension_id   VARCHAR,
    extension_name VARCHAR,
    PRIMARY KEY (binding_id, slot_type, slot_number)
);

CREATE TABLE custom_binding_extension_slot (
    custom_binding_id UUID        NOT NULL REFERENCES custom_binding (id) ON DELETE CASCADE,
    slot_type         VARCHAR(16) NOT NULL,
    slot_number       INT         NOT NULL,
    extension_id      VARCHAR,
    extension_name    VARCHAR,
    PRIMARY KEY (custom_binding_id, slot_type, slot_number)
);

CREATE TABLE user_settings_no_game_extension_slot (
    user_id        UUID        NOT NULL REFERENCES user_settings (user_id) ON DELETE CASCADE,
    slot_type      VARCHAR(16) NOT NULL,
    slot_number    INT         NOT NULL,
    extension_id   VARCHAR,
    extension_name VARCHAR,
    PRIMARY KEY (user_id, slot_type, slot_number)
);

CREATE TABLE user_settings_incomplete_fallback_extension_slot (
    user_id        UUID        NOT NULL REFERENCES user_settings (user_id) ON DELETE CASCADE,
    slot_type      VARCHAR(16) NOT NULL,
    slot_number    INT         NOT NULL,
    extension_id   VARCHAR,
    extension_name VARCHAR,
    PRIMARY KEY (user_id, slot_type, slot_number)
);
