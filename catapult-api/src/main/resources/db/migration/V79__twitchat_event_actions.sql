-- V79__twitchat_event_actions.sql
-- Twitchat event actions: per Catapult event, an ordered list of payloads, each a Twitchat
-- public API action (catapult-common's twitchat/catalog.json) with its data as JSON. An event
-- without an enabled row sends nothing. Replaces twitchat_payload_preset/twitchat_active_preset,
-- which TwitchatEventActionsBootstrap converts at startup and a later migration drops.
CREATE TABLE twitchat_event_action (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_account(id),
    event_type VARCHAR(32) NOT NULL,
    position INT NOT NULL,
    action VARCHAR(64) NOT NULL,
    mode VARCHAR(8) NOT NULL,
    data_json TEXT NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_twitchat_event_action_position UNIQUE (user_id, event_type, position)
);
CREATE INDEX idx_twitchat_event_action_user_event ON twitchat_event_action(user_id, event_type);

-- Whether the account got its event actions (its active presets converted, else the default
-- notifications): done once, at startup for enabled widgets, else when the widget gets enabled.
ALTER TABLE twitchat_widget_settings ADD COLUMN event_actions_initialized BOOLEAN NOT NULL DEFAULT FALSE;
