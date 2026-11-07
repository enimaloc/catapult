CREATE TABLE chat_command_param (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_account(id),
    key VARCHAR(64) NOT NULL,
    value TEXT NOT NULL,
    UNIQUE (user_id, key)
);
