-- Xbox apps (Microsoft Store, Twitch) were detected as games and mapped to random games by the IGDB name search;
-- they no longer are detected, so the auto bindings they created are dead weight. Manual/mapped rows are kept.
DELETE FROM game_binding
WHERE source_type = 'XBOX'
  AND source_name IN ('Microsoft Store', 'Twitch')
  AND status = 'AUTO';
