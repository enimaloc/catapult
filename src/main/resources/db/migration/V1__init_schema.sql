-- ============================================================
-- V1 — Schéma initial Catapult
-- ============================================================

CREATE TYPE account_status AS ENUM ('ACTIVE', 'PENDING_DELETION');
CREATE TYPE oauth_provider AS ENUM ('TWITCH', 'STEAM', 'DISCORD');
CREATE TYPE getter_provider AS ENUM ('STEAM', 'DISCORD');
CREATE TYPE source_type AS ENUM ('STEAM', 'DISCORD', 'MANUAL');
CREATE TYPE binding_status AS ENUM ('AUTO', 'MANUAL', 'INCOMPLETE');
CREATE TYPE twitch_ccl AS ENUM (
    'MatureGame',
    'ViolentGraphic',
    'SexualThemes',
    'LanguageBarrier',
    'DrugUse',
    'Gambling',
    'ProfanityVulgarity'
);

-- ============================================================
-- user_account
-- ============================================================
CREATE TABLE user_account (
    id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    twitch_id             VARCHAR     NOT NULL UNIQUE,
    twitch_username       VARCHAR     NOT NULL,
    bot_enabled           BOOLEAN     NOT NULL DEFAULT TRUE,
    status                account_status NOT NULL DEFAULT 'ACTIVE',
    deletion_requested_at TIMESTAMP,
    created_at            TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP   NOT NULL DEFAULT now()
);

CREATE INDEX idx_account_deletion ON user_account (status, deletion_requested_at);

-- ============================================================
-- oauth_token
-- ============================================================
CREATE TABLE oauth_token (
    id            UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID          NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    provider      oauth_provider NOT NULL,
    access_token  TEXT          NOT NULL,
    refresh_token TEXT,
    expires_at    TIMESTAMP,
    UNIQUE (user_id, provider)
);

-- ============================================================
-- getter_config
-- ============================================================
CREATE TABLE getter_config (
    id        UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id   UUID           NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    provider  getter_provider NOT NULL,
    priority  INT            NOT NULL,
    enabled   BOOLEAN        NOT NULL DEFAULT TRUE,
    UNIQUE (user_id, provider)
);

-- ============================================================
-- game_binding
-- ============================================================
CREATE TABLE game_binding (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID          NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    source_id         VARCHAR,
    source_type       source_type   NOT NULL,
    source_name       VARCHAR       NOT NULL,
    twitch_game_id    VARCHAR,
    twitch_game_name  VARCHAR,
    status            binding_status NOT NULL,
    ignored           BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP     NOT NULL DEFAULT now()
);

CREATE INDEX idx_binding_user_source ON game_binding (user_id, source_id, source_type);

-- ============================================================
-- binding_ccl
-- ============================================================
CREATE TABLE binding_ccl (
    binding_id UUID       NOT NULL REFERENCES game_binding (id) ON DELETE CASCADE,
    ccl        twitch_ccl NOT NULL,
    PRIMARY KEY (binding_id, ccl)
);

-- ============================================================
-- user_settings
-- ============================================================
CREATE TABLE user_settings (
    user_id                UUID    PRIMARY KEY REFERENCES user_account (id) ON DELETE CASCADE,
    ccl_feature_enabled    BOOLEAN NOT NULL DEFAULT TRUE,
    no_game_twitch_game_id VARCHAR,
    no_game_twitch_game_name VARCHAR
);
