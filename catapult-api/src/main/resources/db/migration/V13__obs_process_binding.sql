-- ============================================================
-- V13 — OBS Process Getter : association processus → jeu Twitch
-- ============================================================

CREATE TABLE process_binding (
    id              UUID        NOT NULL PRIMARY KEY,
    user_id         UUID        NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    process_name    VARCHAR(255) NOT NULL,
    twitch_game_id  VARCHAR(50),
    twitch_game_name VARCHAR(255),
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT now(),
    UNIQUE (user_id, process_name)
);

CREATE INDEX idx_process_binding_user ON process_binding(user_id);
