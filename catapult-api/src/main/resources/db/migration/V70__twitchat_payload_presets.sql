-- Twitchat payload presets: lets a streamer fully customize the JSON payload (message,
-- style, icon, author, and per-action label/theme) sent for each of the 5 Twitchat
-- notification event types, via a library of named presets with one active preset per
-- event type. Absence of a row in twitchat_active_preset for a given (user, event_type)
-- means "use the hardcoded default" (see TwitchatDefaultPayloads).
CREATE TABLE twitchat_payload_preset (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_account(id),
    event_type VARCHAR(32) NOT NULL,
    name VARCHAR(255) NOT NULL,
    payload_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_twitchat_payload_preset_user_event ON twitchat_payload_preset(user_id, event_type);

CREATE TABLE twitchat_active_preset (
    user_id UUID NOT NULL REFERENCES user_account(id),
    event_type VARCHAR(32) NOT NULL,
    preset_id UUID NOT NULL REFERENCES twitchat_payload_preset(id),
    PRIMARY KEY (user_id, event_type)
);
