ALTER TABLE user_settings
    ADD COLUMN incomplete_fallback_twitch_game_id   VARCHAR,
    ADD COLUMN incomplete_fallback_twitch_game_name VARCHAR;

CREATE TABLE user_settings_incomplete_fallback_ccls (
    user_id UUID         NOT NULL REFERENCES user_settings(user_id) ON DELETE CASCADE,
    ccl_id  VARCHAR(64)  NOT NULL,
    PRIMARY KEY (user_id, ccl_id)
);
