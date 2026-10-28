-- How recently (minutes) a game must have been detected for an outgoing raid to put it back as the category.
-- NULL: off (the default).
ALTER TABLE user_settings ADD COLUMN raid_restore_game_minutes INTEGER;
ALTER TABLE user_settings ADD CONSTRAINT chk_user_settings_raid_restore_game_minutes
    CHECK (raid_restore_game_minutes IS NULL OR raid_restore_game_minutes BETWEEN 1 AND 120);
