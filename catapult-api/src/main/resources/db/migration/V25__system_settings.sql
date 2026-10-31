-- ============================================================
-- V25 — system settings key-value store
-- ============================================================

CREATE TABLE system_settings (
    key   VARCHAR(255) NOT NULL PRIMARY KEY,
    value VARCHAR(255) NOT NULL
);
