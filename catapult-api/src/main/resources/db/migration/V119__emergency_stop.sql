-- Viewer reports of a provider misbehaving (wrong game detected) and the stops they, or the owner, trigger.
CREATE TABLE emergency_report (
    id                  UUID PRIMARY KEY,
    channel_user_id     UUID        NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    provider            TEXT        NOT NULL,
    reported_by_twitch_id TEXT     NOT NULL,
    reported_by_login   TEXT        NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    withdrawn_at        TIMESTAMPTZ,
    stream_id           TEXT,
    stream_started_at   TIMESTAMPTZ,
    vod_id              TEXT,
    context             JSONB
);

CREATE INDEX idx_emergency_report_provider_created ON emergency_report(provider, created_at);
CREATE INDEX idx_emergency_report_channel ON emergency_report(channel_user_id);

CREATE TABLE provider_emergency_stop (
    id                  UUID PRIMARY KEY,
    provider            TEXT        NOT NULL,
    scope               TEXT        NOT NULL,
    channel_user_id     UUID        REFERENCES user_account(id) ON DELETE CASCADE,
    origin              TEXT        NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ,
    lifted_at           TIMESTAMPTZ,
    lifted_by           TEXT
);

CREATE INDEX idx_provider_emergency_stop_lifted ON provider_emergency_stop(lifted_at);
CREATE INDEX idx_provider_emergency_stop_channel ON provider_emergency_stop(channel_user_id);
