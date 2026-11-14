-- V113__ankama_presence.sql
CREATE TABLE ankama_service_account (
    id                   UUID PRIMARY KEY,
    api_key_enc          TEXT        NOT NULL,
    refresh_token_enc    TEXT        NOT NULL,
    ankama_account_id    BIGINT      NOT NULL UNIQUE,
    nickname             TEXT        NOT NULL,
    tag                  TEXT        NOT NULL,
    device_id            TEXT        NOT NULL,
    hm1                  TEXT        NOT NULL,
    certificate_id       BIGINT,
    certificate_enc      TEXT,
    last_refresh_at      TIMESTAMPTZ NOT NULL,
    auth_state           TEXT        NOT NULL DEFAULT 'READY',
    fill_order           INT         NOT NULL DEFAULT 0,
    friend_limit_reached BOOLEAN     NOT NULL DEFAULT FALSE,
    enabled              BOOLEAN     NOT NULL DEFAULT TRUE,
    version              BIGINT      NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ankama_friend_link (
    id                   UUID PRIMARY KEY,
    user_id              UUID        NOT NULL UNIQUE REFERENCES user_account(id) ON DELETE CASCADE,
    service_account_id   UUID        NOT NULL REFERENCES ankama_service_account(id) ON DELETE CASCADE,
    requested_name       TEXT        NOT NULL,
    requested_tag        TEXT        NOT NULL,
    ankama_user_id       BIGINT,
    status               TEXT        NOT NULL DEFAULT 'PENDING',
    requested_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    accepted_at          TIMESTAMPTZ
);

CREATE INDEX idx_ankama_friend_link_service_account ON ankama_friend_link(service_account_id);
CREATE INDEX idx_ankama_friend_link_status ON ankama_friend_link(status);
CREATE INDEX idx_ankama_friend_link_ankama_user ON ankama_friend_link(ankama_user_id);
