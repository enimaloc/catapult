-- V19 — no-game CCLs: store CCLs to apply when no game is detected
CREATE TABLE user_settings_no_game_ccls (
    user_id UUID        NOT NULL REFERENCES user_settings(user_id) ON DELETE CASCADE,
    ccl_id  VARCHAR(64) NOT NULL,
    PRIMARY KEY (user_id, ccl_id)
);
