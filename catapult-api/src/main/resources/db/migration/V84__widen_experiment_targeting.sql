-- ============================================================
-- V84 — widen the experiment targeting columns
-- The admin Experiments tab targets tags ("flag:" + a key of up to 255) and
-- tag values (up to 1024), which overflowed the original VARCHAR(50)/(100).
-- ============================================================

ALTER TABLE experiment_assignment_rules
    ALTER COLUMN attribute_key   TYPE VARCHAR(260),
    ALTER COLUMN attribute_value TYPE VARCHAR(1024);

ALTER TABLE experiment_overrides
    ALTER COLUMN attribute_key TYPE VARCHAR(260),
    ALTER COLUMN attribute_val TYPE VARCHAR(1024);
