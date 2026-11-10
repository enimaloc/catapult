-- The stream titles of the two fallbacks, winning over the channel-wide one: with the "no game" category, and with
-- the one set for a game whose category isn't known yet. NULL: the channel-wide one.
ALTER TABLE user_settings ADD COLUMN no_game_title VARCHAR(140);
ALTER TABLE user_settings ADD COLUMN incomplete_fallback_title VARCHAR(140);
