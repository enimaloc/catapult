ALTER TABLE igdb_game_details ADD COLUMN rating DOUBLE PRECISION;
ALTER TABLE igdb_game_details ADD COLUMN aggregated_rating DOUBLE PRECISION;
ALTER TABLE igdb_game_details ADD COLUMN platforms_json JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE igdb_game_details ADD COLUMN dlc_names_json JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE igdb_game_details ADD COLUMN similar_game_names_json JSONB NOT NULL DEFAULT '[]'::jsonb;
