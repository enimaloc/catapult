-- The osu! OAuth application Catapult reads the members' osu! profiles with: its client id and secret, set by an
-- admin. One row at most (id = 1). The secret is encrypted (TokenEncryptionService). Without the row the osu! source
-- is off and the osu! login is not registered.
CREATE TABLE osu_app_credentials (
    id                SMALLINT    PRIMARY KEY CHECK (id = 1),
    client_id         TEXT        NOT NULL,
    client_secret_enc TEXT        NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,
    version           BIGINT      NOT NULL DEFAULT 0
);

-- A member's osu! account, linked through osu!'s OAuth (scopes identify and public): the osu! user id, the username
-- and default ruleset seen at the last link, and the refresh token (encrypted, replaced on every refresh) the reads
-- need. The access token is never stored. `invalid_since` is set once osu! refused the refresh token: the card asks
-- to link again. Unlinking or deleting the member's account deletes the row. `version` (optimistic locking) keeps a
-- refresh and a new link from overwriting each other silently.
CREATE TABLE osu_account (
    user_id           UUID        PRIMARY KEY REFERENCES user_account (id) ON DELETE CASCADE,
    osu_user_id       BIGINT      NOT NULL,
    username          TEXT,
    playmode          TEXT,
    refresh_token_enc TEXT,
    invalid_since     TIMESTAMPTZ,
    linked_at         TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,
    version           BIGINT      NOT NULL DEFAULT 0
);
