-- Twitchat integration: per-streamer widget link/OBS config, one-shot action
-- tokens for notification buttons, and a marker distinguishing category
-- changes Catapult made itself from manual streamer changes (Twitch's
-- EventSub channel.update gives no such distinction).
CREATE TABLE twitchat_widget_settings (
    user_id UUID PRIMARY KEY REFERENCES user_account(id),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    widget_token UUID NOT NULL UNIQUE,
    obs_host VARCHAR(255),
    obs_port INTEGER,
    obs_password_encrypted VARCHAR(512)
);

CREATE TABLE twitchat_action_token (
    token UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_account(id),
    action_type VARCHAR(32) NOT NULL,
    payload_json TEXT,
    expires_at TIMESTAMP NOT NULL,
    consumed_at TIMESTAMP
);
CREATE INDEX idx_twitchat_action_token_user ON twitchat_action_token(user_id);

CREATE TABLE catapult_category_change_state (
    user_id UUID PRIMARY KEY REFERENCES user_account(id),
    game_id VARCHAR(64),
    previous_game_id VARCHAR(64),
    applied_at TIMESTAMP
);
