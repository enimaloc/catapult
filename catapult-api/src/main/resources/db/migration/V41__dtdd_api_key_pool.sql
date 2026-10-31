CREATE TABLE dtdd_api_key (
    api_key      VARCHAR(255) NOT NULL,
    owner        UUID         NULL REFERENCES user_account(id) ON DELETE CASCADE,
    is_exclusive BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (api_key)
);
