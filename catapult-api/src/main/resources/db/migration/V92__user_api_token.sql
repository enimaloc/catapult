-- A member's personal API token (at most one), for /api/v2/custom-bindings only. Only its SHA-256 (hex) is kept:
-- the token itself is shown once, when it is generated.
CREATE TABLE user_api_token (
    user_id    UUID      PRIMARY KEY REFERENCES user_account (id) ON DELETE CASCADE,
    token_hash CHAR(64)  NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL
);
