-- V116__epic_presence.sql
CREATE TABLE epic_service_account (
    id                   UUID PRIMARY KEY,
    refresh_token_enc    TEXT        NOT NULL,
    refresh_expires_at   TIMESTAMPTZ NOT NULL,
    epic_account_id      TEXT        NOT NULL UNIQUE,
    display_name         TEXT        NOT NULL,
    last_refresh_at      TIMESTAMPTZ NOT NULL,
    auth_state           TEXT        NOT NULL DEFAULT 'READY',
    fill_order           INT         NOT NULL DEFAULT 0,
    friend_limit_reached BOOLEAN     NOT NULL DEFAULT FALSE,
    enabled              BOOLEAN     NOT NULL DEFAULT TRUE,
    version              BIGINT      NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE epic_friend_link (
    id                   UUID PRIMARY KEY,
    user_id              UUID        NOT NULL UNIQUE REFERENCES user_account(id) ON DELETE CASCADE,
    service_account_id   UUID        NOT NULL REFERENCES epic_service_account(id) ON DELETE CASCADE,
    epic_account_id      TEXT        NOT NULL,
    display_name         TEXT        NOT NULL,
    status               TEXT        NOT NULL DEFAULT 'PENDING',
    requested_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    accepted_at          TIMESTAMPTZ
);

CREATE INDEX idx_epic_friend_link_service_account ON epic_friend_link(service_account_id);
CREATE INDEX idx_epic_friend_link_status ON epic_friend_link(status);

-- One active link per Epic account: a removed link must not reserve it forever
CREATE UNIQUE INDEX uq_epic_friend_link_epic_account ON epic_friend_link (epic_account_id)
    WHERE status <> 'REMOVED';
