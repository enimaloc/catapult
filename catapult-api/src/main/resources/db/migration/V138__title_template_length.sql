-- A title's variables no longer count in its 140 characters: a template may hold up to 500 as typed
ALTER TABLE user_settings
    ALTER COLUMN title_template            TYPE VARCHAR(500),
    ALTER COLUMN no_game_title             TYPE VARCHAR(500),
    ALTER COLUMN incomplete_fallback_title TYPE VARCHAR(500);
ALTER TABLE game_binding ALTER COLUMN title TYPE VARCHAR(500);
ALTER TABLE custom_binding ALTER COLUMN title TYPE VARCHAR(500);
