-- LanguageBarrier is not a valid Twitch CCL label — remove all occurrences.
-- KW_LANGUAGE keywords already map to ProfanityVulgarity in the detection logic.
DELETE FROM binding_ccl WHERE ccl = 'LanguageBarrier';
UPDATE igdb_game_ccl_cache
    SET ccls = REPLACE(ccls, 'LanguageBarrier,', '')
    WHERE ccls LIKE '%LanguageBarrier%';
UPDATE igdb_game_ccl_cache
    SET ccls = REPLACE(ccls, ',LanguageBarrier', '')
    WHERE ccls LIKE '%LanguageBarrier%';
UPDATE igdb_game_ccl_cache
    SET ccls = REPLACE(ccls, 'LanguageBarrier', '')
    WHERE ccls LIKE '%LanguageBarrier%';
