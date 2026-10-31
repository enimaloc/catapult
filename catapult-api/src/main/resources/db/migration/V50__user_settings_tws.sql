ALTER TABLE user_settings ADD COLUMN tw_feature_enabled BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE user_settings_blocked_tws (
    user_id UUID         NOT NULL REFERENCES user_settings(user_id) ON DELETE CASCADE,
    tw_id   VARCHAR(40)  NOT NULL,
    PRIMARY KEY (user_id, tw_id)
);
