-- V30 — CCL blocklist: CCLs the user never wants applied to their stream
CREATE TABLE user_settings_blocked_ccls (
    user_id UUID        NOT NULL REFERENCES user_settings(user_id) ON DELETE CASCADE,
    ccl_id  VARCHAR(64) NOT NULL,
    PRIMARY KEY (user_id, ccl_id)
);
