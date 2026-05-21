-- ============================================================
-- V20 — A/B testing
-- ============================================================

CREATE TABLE experiments (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    key         VARCHAR(100) NOT NULL UNIQUE,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    status      VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    started_at  TIMESTAMP,
    ended_at    TIMESTAMP,
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE experiment_variants (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    experiment_id UUID        NOT NULL REFERENCES experiments(id) ON DELETE CASCADE,
    key           VARCHAR(100) NOT NULL,
    name          VARCHAR(255) NOT NULL,
    weight        INT          NOT NULL DEFAULT 1,
    is_control    BOOLEAN      NOT NULL DEFAULT false
);

CREATE TABLE experiment_assignment_rules (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    experiment_id     UUID        NOT NULL REFERENCES experiments(id) ON DELETE CASCADE,
    rule_type         VARCHAR(20) NOT NULL,  -- RANDOM | ATTRIBUTE | MANUAL
    priority          INT         NOT NULL DEFAULT 0,
    percentage        INT,                   -- RANDOM: 0-100
    attribute_key     VARCHAR(50),           -- ATTRIBUTE: binding_count | account_age_days | has_steam | has_xbox | has_battlenet
    attribute_operator VARCHAR(10),          -- ATTRIBUTE: eq | neq | gt | gte | lt | lte
    attribute_value   VARCHAR(100)           -- ATTRIBUTE: comparison value as string
);

CREATE TABLE experiment_assignments (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    experiment_id UUID        NOT NULL REFERENCES experiments(id) ON DELETE CASCADE,
    variant_id    UUID        NOT NULL REFERENCES experiment_variants(id) ON DELETE CASCADE,
    user_id       UUID        NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    assigned_at   TIMESTAMP   NOT NULL DEFAULT now(),
    UNIQUE (experiment_id, user_id)
);

CREATE TABLE experiment_events (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    experiment_id UUID        NOT NULL REFERENCES experiments(id) ON DELETE CASCADE,
    variant_id    UUID        NOT NULL REFERENCES experiment_variants(id) ON DELETE CASCADE,
    user_id       UUID        NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    event_key     VARCHAR(100) NOT NULL,
    occurred_at   TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE experiment_feedback (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    experiment_id UUID        NOT NULL REFERENCES experiments(id) ON DELETE CASCADE,
    variant_id    UUID        NOT NULL REFERENCES experiment_variants(id) ON DELETE CASCADE,
    user_id       UUID        NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    nps_score     INT         NOT NULL CHECK (nps_score BETWEEN 0 AND 10),
    comment       TEXT,
    submitted_at  TIMESTAMP   NOT NULL DEFAULT now(),
    UNIQUE (experiment_id, user_id)
);

CREATE INDEX idx_experiment_events_lookup ON experiment_events (experiment_id, variant_id, event_key);
CREATE INDEX idx_experiment_feedback_lookup ON experiment_feedback (experiment_id, variant_id);
CREATE INDEX idx_experiment_rules ON experiment_assignment_rules (experiment_id, priority);
