CREATE TABLE user_flags (
    id         UUID PRIMARY KEY,
    user_id    UUID NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    flag_key   VARCHAR(255) NOT NULL,
    flag_value VARCHAR(1024),
    UNIQUE (user_id, flag_key)
);
