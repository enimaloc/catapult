-- ============================================================
-- V86 — end the chat.commands experiment
-- Streamers' own chat commands move to their other chat bots (the Commands tab walks them through
-- it, answered by the user API): Catapult stops answering them in chat.
-- ============================================================

UPDATE experiments
SET status = 'ENDED', ended_at = COALESCE(ended_at, now())
WHERE key = 'chat.commands' AND status <> 'ENDED';
