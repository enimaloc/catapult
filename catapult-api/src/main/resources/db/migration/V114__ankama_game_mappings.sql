-- Ankama games (source_id: the launcher's applicationId). Never overwrites a mapping an admin already made.
INSERT INTO game_mapping (source_type, source_id, twitch_game_id, twitch_game_name, igdb_id)
VALUES ('ANKAMA', '1', '20596', 'Dofus', '18337'),
       ('ANKAMA', '3', '21136', 'WAKFU', '9280'),
       ('ANKAMA', '17', '493754', 'Krosmaga', '29097'),
       ('ANKAMA', '22', '511817', 'Waven', '109536')
ON CONFLICT (source_type, source_id) DO NOTHING;
