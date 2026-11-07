-- Les placeholders passent de {game.name} à {game#name} : Twitch détecte les
-- chemins avec des points comme des liens (≈ noms de domaine) et peut bloquer
-- le message. On ne remplace les points qu'à l'intérieur des tokens {…}, pas
-- dans le texte libre des templates. Trois passes suffisent (2 points max par
-- chemin : game.store.steam).
UPDATE chat_command_definition
SET template = regexp_replace(
                   regexp_replace(
                       regexp_replace(template, '(\{[a-z_#]+)\.', '\1#', 'g'),
                       '(\{[a-z_#]+)\.', '\1#', 'g'),
                   '(\{[a-z_#]+)\.', '\1#', 'g')
WHERE template LIKE '%{%.%';

-- Colonne "placeholder" = chemin nu, remplacement direct sans risque.
UPDATE chat_command_fallback
SET placeholder = replace(placeholder, '.', '#')
WHERE placeholder LIKE '%.%';
