ALTER TABLE user_settings
    ADD COLUMN apply_default_on_stream_start BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN apply_default_on_no_game      BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN apply_default_on_stream_end   BOOLEAN NOT NULL DEFAULT TRUE;
