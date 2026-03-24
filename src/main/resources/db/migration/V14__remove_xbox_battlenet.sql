-- Remove Xbox and BattleNet data — providers no longer supported
DELETE FROM getter_config WHERE provider IN ('XBOX', 'BATTLENET');
DELETE FROM oauth_token WHERE provider IN ('XBOX', 'BATTLENET');
