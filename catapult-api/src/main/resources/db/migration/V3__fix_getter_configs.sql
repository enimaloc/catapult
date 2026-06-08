-- Supprimer les entrées DISCORD devenues invalides
DELETE FROM getter_config WHERE provider = 'DISCORD';

-- Initialiser les getter configs STEAM pour les comptes existants qui n'en ont pas
INSERT INTO getter_config (id, user_id, provider, priority, enabled)
SELECT gen_random_uuid(), ua.id, 'STEAM', 1, true
FROM user_account ua
WHERE NOT EXISTS (
    SELECT 1 FROM getter_config gc
    WHERE gc.user_id = ua.id AND gc.provider = 'STEAM'
);

-- Initialiser les getter configs XBOX pour les comptes existants qui n'en ont pas
INSERT INTO getter_config (id, user_id, provider, priority, enabled)
SELECT gen_random_uuid(), ua.id, 'XBOX', 2, false
FROM user_account ua
WHERE NOT EXISTS (
    SELECT 1 FROM getter_config gc
    WHERE gc.user_id = ua.id AND gc.provider = 'XBOX'
);

-- Initialiser les getter configs BATTLENET pour les comptes existants qui n'en ont pas
INSERT INTO getter_config (id, user_id, provider, priority, enabled)
SELECT gen_random_uuid(), ua.id, 'BATTLENET', 3, false
FROM user_account ua
WHERE NOT EXISTS (
    SELECT 1 FROM getter_config gc
    WHERE gc.user_id = ua.id AND gc.provider = 'BATTLENET'
);
