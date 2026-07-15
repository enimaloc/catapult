-- ============================================================
-- V61 — add the "tab" variant to the invite-button-placement experiment
-- ============================================================
-- ExperimentSynchronizer only creates an experiment when no DB row exists
-- for its key yet, so updating the @ExperimentSpec on an already-synced
-- experiment has no effect. On a fresh database this is a no-op (the
-- experiment row doesn't exist yet — the synchronizer will create the full
-- 4-variant experiment straight from the current spec on first boot).

UPDATE experiment_variants
SET weight = 25
WHERE experiment_id = (SELECT id FROM experiments WHERE key = 'invite-button-placement')
  AND key IN ('nav-default', 'nav-end', 'card');

INSERT INTO experiment_variants (experiment_id, internal_id, key, name, weight, is_control)
SELECT e.id, 3, 'tab', 'Onglet Invitations (page chaîne à onglets)', 25, false
FROM experiments e
WHERE e.key = 'invite-button-placement'
  AND NOT EXISTS (
      SELECT 1 FROM experiment_variants ev
      WHERE ev.experiment_id = e.id AND ev.key = 'tab'
  );
