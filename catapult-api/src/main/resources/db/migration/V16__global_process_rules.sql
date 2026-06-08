-- ============================================================
-- V16 — Global process rules : règles admin sans propriétaire
-- ============================================================

-- Rendre user_id nullable (NULL = règle globale admin)
ALTER TABLE process_binding ALTER COLUMN user_id DROP NOT NULL;

-- Remplacer la contrainte unique globale par une contrainte partielle
-- (les règles globales n'ont pas de contrainte d'unicité sur process_name)
ALTER TABLE process_binding DROP CONSTRAINT process_binding_user_id_process_name_key;
CREATE UNIQUE INDEX uq_process_binding_user
    ON process_binding (user_id, process_name)
    WHERE user_id IS NOT NULL;

-- Flag pour distinguer nom exact vs pattern regex
ALTER TABLE process_binding ADD COLUMN is_regex BOOLEAN NOT NULL DEFAULT FALSE;
