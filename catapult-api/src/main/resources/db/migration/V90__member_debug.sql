-- Per-member debug mode: the admin-controlled state, and the HTTP exchanges recorded while it is on.
CREATE TABLE member_debug_session (
    user_id        UUID        PRIMARY KEY REFERENCES user_account (id) ON DELETE CASCADE,
    active         BOOLEAN     NOT NULL,
    reveal_secrets BOOLEAN     NOT NULL DEFAULT FALSE,
    enabled_by     UUID,
    enabled_at     TIMESTAMPTZ NOT NULL,
    -- set when the mode is switched off; the exchanges are purged 30 days later. NULL while active.
    disabled_at    TIMESTAMPTZ
);

CREATE TABLE debug_exchange (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID         NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    occurred_at      TIMESTAMPTZ  NOT NULL,
    direction        VARCHAR(3)   NOT NULL,
    service          VARCHAR(32)  NOT NULL,
    operation        VARCHAR(128),
    method           VARCHAR(16)  NOT NULL,
    url              TEXT         NOT NULL,
    request_headers  TEXT,
    request_body     TEXT,
    status           INT,
    response_headers TEXT,
    response_body    TEXT,
    error            TEXT,
    duration_ms      BIGINT       NOT NULL,
    filtered         BOOLEAN      NOT NULL DEFAULT FALSE,
    trace_id         VARCHAR(64)
);

CREATE INDEX idx_debug_exchange_user_time ON debug_exchange (user_id, occurred_at DESC);
