-- The share of a debugged member's pages the browser replays whole, chosen by the admin who switched the mode on.
ALTER TABLE member_debug_session
    ADD COLUMN replay_rate DOUBLE PRECISION NOT NULL DEFAULT 1.0 CHECK (replay_rate BETWEEN 0 AND 1);
