-- Twitch CCL label "DrugUse" is invalid; the correct ID is "DrugsIntoxication"
UPDATE binding_ccl
    SET ccl = 'DrugsIntoxication'
    WHERE ccl = 'DrugUse';

UPDATE igdb_game_ccl_cache
    SET ccls = REPLACE(ccls, 'DrugUse', 'DrugsIntoxication')
    WHERE ccls LIKE '%DrugUse%';
