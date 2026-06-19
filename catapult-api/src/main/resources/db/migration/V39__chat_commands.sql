-- Cache enrichi des détails IGDB (stale-while-revalidate)
CREATE TABLE igdb_game_details (
    igdb_id            VARCHAR(64)  PRIMARY KEY,
    slug               VARCHAR(255),
    summary            TEXT,
    first_release_date TIMESTAMPTZ,
    websites_json      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    fetched_at         TIMESTAMPTZ  NOT NULL
);

-- Définition d'une commande chat data-driven
CREATE TABLE chat_command_definition (
    id          UUID         PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    name        VARCHAR(32)  NOT NULL,
    template    TEXT         NOT NULL,
    permission  VARCHAR(16)  NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT true,
    preset_key  VARCHAR(64),
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_chat_cmd_user_name UNIQUE (user_id, name)
);

CREATE INDEX idx_chat_cmd_user ON chat_command_definition(user_id);

-- Fallbacks par placeholder pour une commande
CREATE TABLE chat_command_fallback (
    command_id    UUID         NOT NULL REFERENCES chat_command_definition(id) ON DELETE CASCADE,
    placeholder   VARCHAR(64)  NOT NULL,
    fallback_text VARCHAR(200) NOT NULL,
    PRIMARY KEY (command_id, placeholder)
);

-- Support du token système dans oauth_token (user_id nullable, provider SYSTEM)
ALTER TABLE oauth_token
    ALTER COLUMN user_id DROP NOT NULL;

-- L'unicité existante UNIQUE(user_id, provider) tolère plusieurs NULL en Postgres,
-- on ajoute un index partiel pour garantir un seul token SYSTEM.
CREATE UNIQUE INDEX uk_oauth_token_system
    ON oauth_token (provider)
    WHERE user_id IS NULL AND provider = 'SYSTEM';
