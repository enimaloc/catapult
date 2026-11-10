-- The stream title Catapult sends along with the category. Off by default: the streamer's title is left alone
-- until they turn it on.
ALTER TABLE user_settings ADD COLUMN title_feature_enabled BOOLEAN NOT NULL DEFAULT FALSE;
-- The channel-wide title, {{GAME}} standing for the Twitch category, {{TITLE}} for the title before. NULL: none.
ALTER TABLE user_settings ADD COLUMN title_template VARCHAR(140);
-- A game's own title, winning over the channel-wide one. NULL: the channel-wide one.
ALTER TABLE game_binding ADD COLUMN title VARCHAR(140);
