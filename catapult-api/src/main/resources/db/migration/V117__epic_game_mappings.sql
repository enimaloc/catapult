-- Epic games (source_id: the Epic catalog productId). Never overwrites a mapping an admin already made.
INSERT INTO game_mapping (source_type, source_id, twitch_game_id, twitch_game_name, igdb_id)
VALUES ('EPIC', 'd42d7101bb7a41c09a810ee418b7d3d0', '512980', 'Fall Guys', '119313')
ON CONFLICT (source_type, source_id) DO NOTHING;
