-- A game mapping can pin its source to an IGDB game (null: resolved as without a mapping), which is how a platform
-- IGDB cannot look up by ID (Battle.net) gets its IGDB data.
ALTER TABLE game_mapping ADD COLUMN igdb_id TEXT;

-- Battle.net titles (source_id: the Blizzard product code). Never overwrites a mapping an admin already made.
INSERT INTO game_mapping (source_type, source_id, twitch_game_id, twitch_game_name, igdb_id)
VALUES ('BATTLENET', 'WoW', '18122', 'World of Warcraft', '123'),
       ('BATTLENET', 'WTCG', '138585', 'Hearthstone', '1279'),
       ('BATTLENET', 'Pro', '515025', 'Overwatch', '125174'),
       ('BATTLENET', 'Fen', '515024', 'Diablo IV', '125165'),
       ('BATTLENET', 'D3', '313558', 'Diablo III', '120'),
       ('BATTLENET', 'OSI', '1788326126', 'Diablo II: Resurrected', '142803'),
       ('BATTLENET', 'S2', '490422', 'StarCraft II', '239'),
       ('BATTLENET', 'Hero', '32959', 'Heroes of the Storm', '7313'),
       ('BATTLENET', 'W3', '12924', 'Warcraft III', '111650')
ON CONFLICT (source_type, source_id) DO NOTHING;
