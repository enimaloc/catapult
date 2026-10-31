-- ============================================================
-- V85 — end the invite-button-placement experiment
-- The new UI has its own Invitations tab: no placement is tested any more.
-- ============================================================

UPDATE experiments
SET status = 'ENDED', ended_at = COALESCE(ended_at, now())
WHERE key = 'invite-button-placement' AND status <> 'ENDED';
