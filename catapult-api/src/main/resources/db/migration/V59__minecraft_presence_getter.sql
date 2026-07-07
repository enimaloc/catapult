-- V59__minecraft_presence_getter.sql
CREATE TABLE minecraft_service_account (
    id                   UUID PRIMARY KEY,
    label                TEXT        NOT NULL,
    minecraft_username   TEXT        NOT NULL,
    msa_refresh_token    TEXT        NOT NULL,
    fill_order           INT         NOT NULL DEFAULT 0,
    friend_limit_reached BOOLEAN     NOT NULL DEFAULT FALSE,
    enabled              BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE minecraft_friend_link (
    id                   UUID PRIMARY KEY,
    user_id              UUID        NOT NULL UNIQUE REFERENCES user_account(id) ON DELETE CASCADE,
    service_account_id   UUID        NOT NULL REFERENCES minecraft_service_account(id) ON DELETE CASCADE,
    minecraft_profile_id TEXT        NOT NULL,
    minecraft_name       TEXT        NOT NULL,
    status               TEXT        NOT NULL DEFAULT 'PENDING',
    requested_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    accepted_at          TIMESTAMPTZ
);

CREATE INDEX idx_mc_friend_link_service_account ON minecraft_friend_link(service_account_id);
CREATE INDEX idx_mc_friend_link_status ON minecraft_friend_link(status);
