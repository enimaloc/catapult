-- The Battle.net mobile app (BSAp) was detected as a game; it no longer is, so its bindings are dead weight.
DELETE FROM game_binding
WHERE source_type = 'BATTLENET'
  AND source_id = 'BSAp';
