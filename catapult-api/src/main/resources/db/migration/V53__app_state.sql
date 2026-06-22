CREATE TABLE app_state (
    state_key  VARCHAR(80) PRIMARY KEY,
    value      TEXT        NOT NULL,
    updated_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
