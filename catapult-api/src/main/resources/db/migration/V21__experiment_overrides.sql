-- ============================================================
-- V21 — experiment overrides
-- ============================================================

CREATE TABLE experiment_overrides (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    experiment_id     UUID        NOT NULL REFERENCES experiments(id) ON DELETE CASCADE,
    override_type     VARCHAR(20) NOT NULL,    -- USER | ATTRIBUTE
    action            VARCHAR(20) NOT NULL,    -- FORCE_INCLUDE | FORCE_EXCLUDE | FORCE_VARIANT
    priority          INT         NOT NULL DEFAULT 0,
    target_user_id    UUID        REFERENCES user_account(id) ON DELETE CASCADE,
    attribute_key     VARCHAR(50),
    attribute_op      VARCHAR(10),
    attribute_val     VARCHAR(100),
    target_variant_id UUID        REFERENCES experiment_variants(id) ON DELETE SET NULL
);

CREATE INDEX idx_experiment_overrides ON experiment_overrides (experiment_id, priority);
