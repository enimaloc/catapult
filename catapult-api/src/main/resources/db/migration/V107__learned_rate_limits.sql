-- What Catapult learned of the rate limits platforms don't document, from their 429s: one row per host (its shared
-- budget, method and path NULL) and one per route seen (scope HOST: counted in its host's budget; ROUTE: its own).
-- override_*: an admin's correction, kept until reset; the learning leaves it alone.
CREATE TABLE learned_rate_limits (
    id                      VARCHAR(800) PRIMARY KEY,
    host                    VARCHAR(255) NOT NULL,
    method                  VARCHAR(16),
    path                    VARCHAR(512),
    scope                   VARCHAR(8),
    scope_pinned            BOOLEAN      NOT NULL DEFAULT FALSE,
    limit_calls             INTEGER,
    limit_window_seconds    INTEGER,
    resume_ms               BIGINT       NOT NULL,
    resume_fail_ms          BIGINT,
    resume_ok_ms            BIGINT,
    last_fail_at            TIMESTAMPTZ,
    rate_limited_count      INTEGER      NOT NULL DEFAULT 0,
    last_rate_limited_at    TIMESTAMPTZ,
    override_calls          INTEGER,
    override_window_seconds INTEGER,
    override_resume_ms      BIGINT,
    updated_at              TIMESTAMPTZ  NOT NULL
);
