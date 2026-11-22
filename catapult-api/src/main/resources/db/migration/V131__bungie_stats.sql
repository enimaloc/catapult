-- The Bungie.net application Catapult reads Destiny 2 with: its OAuth client (id, secret) and the API key bound to
-- it, set by an admin. One row at most (id = 1): the key belongs to the client, a rotation makes no sense. The secret
-- and the key are encrypted (TokenEncryptionService). Without the row the Bungie source is off.
CREATE TABLE bungie_app_credentials (
    id                SMALLINT    PRIMARY KEY CHECK (id = 1),
    client_id         TEXT        NOT NULL,
    client_secret_enc TEXT        NOT NULL,
    api_key_enc       TEXT        NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,
    version           BIGINT      NOT NULL DEFAULT 0
);

-- A member's Bungie.net account, linked through Bungie's OAuth: the Bungie.net membership, the Destiny 2 one their
-- stats are read from, and the refresh token (encrypted, 90 days, rotated on every refresh) the reads need. The access
-- token is never stored. `invalid_since` is set once Bungie refused the refresh token: the card asks to link again.
-- Unlinking or deleting the member's account deletes the row. `version` (optimistic locking) keeps a refresh and an
-- unlink from overwriting each other silently.
CREATE TABLE bungie_account (
    user_id               UUID        PRIMARY KEY REFERENCES user_account (id) ON DELETE CASCADE,
    membership_id         TEXT        NOT NULL,
    display_name          TEXT,
    destiny_membership_id TEXT,
    membership_type       INTEGER,
    refresh_token_enc     TEXT,
    refresh_expires_at    TIMESTAMPTZ,
    invalid_since         TIMESTAMPTZ,
    linked_at             TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL,
    version               BIGINT      NOT NULL DEFAULT 0
);
