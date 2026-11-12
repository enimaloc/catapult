-- An admin game mapping can ignore its game by default (a background app such as Wallpaper Engine): its bindings are
-- ignored, but those the streamer ignored or not by hand. Such a mapping needs no Twitch category: without one, its
-- bindings keep IGDB's.
ALTER TABLE game_mapping ADD COLUMN ignored_by_default BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE game_mapping ALTER COLUMN twitch_game_id DROP NOT NULL;
ALTER TABLE game_mapping ALTER COLUMN twitch_game_name DROP NOT NULL;

-- true: the streamer chose whether the binding is ignored, an admin mapping no longer changes it.
ALTER TABLE game_binding ADD COLUMN ignored_override BOOLEAN NOT NULL DEFAULT FALSE;
-- Before mappings could ignore a game, only the streamer did.
UPDATE game_binding SET ignored_override = TRUE WHERE ignored;
