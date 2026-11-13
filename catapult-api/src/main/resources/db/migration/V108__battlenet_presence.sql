-- V108__battlenet_presence.sql
CREATE TABLE battlenet_service_account (
    id                   UUID PRIMARY KEY,
    label                TEXT        NOT NULL,
    email                TEXT        NOT NULL,
    password_enc         TEXT        NOT NULL,
    storage_state_enc    TEXT,
    battle_tag           TEXT,
    bnet_account_id      BIGINT UNIQUE,
    auth_state           TEXT        NOT NULL DEFAULT 'NEW',
    fill_order           INT         NOT NULL DEFAULT 0,
    friend_limit_reached BOOLEAN     NOT NULL DEFAULT FALSE,
    enabled              BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE battlenet_friend_link (
    id                   UUID PRIMARY KEY,
    user_id              UUID        NOT NULL UNIQUE REFERENCES user_account(id) ON DELETE CASCADE,
    service_account_id   UUID        NOT NULL REFERENCES battlenet_service_account(id) ON DELETE CASCADE,
    bnet_account_id      BIGINT      NOT NULL,
    battle_tag           TEXT        NOT NULL,
    invitation_id        BIGINT,
    status               TEXT        NOT NULL DEFAULT 'PENDING',
    requested_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    accepted_at          TIMESTAMPTZ
);

CREATE INDEX idx_bnet_friend_link_service_account ON battlenet_friend_link(service_account_id);
CREATE INDEX idx_bnet_friend_link_status ON battlenet_friend_link(status);
CREATE INDEX idx_bnet_friend_link_bnet_account ON battlenet_friend_link(bnet_account_id);
