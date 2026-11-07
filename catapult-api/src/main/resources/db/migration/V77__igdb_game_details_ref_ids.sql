ALTER TABLE igdb_game_details ADD COLUMN dlc_ids_json JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE igdb_game_details ADD COLUMN similar_game_ids_json JSONB NOT NULL DEFAULT '[]'::jsonb;
