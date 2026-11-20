-- Per-member dry run, set by an admin: while the row exists, Catapult writes nothing public on the member's Twitch
-- channel. Switching it off (by the admin or the member) deletes the row.
CREATE TABLE member_dry_run (
    user_id    UUID        PRIMARY KEY REFERENCES user_account (id) ON DELETE CASCADE,
    enabled_by UUID,
    enabled_at TIMESTAMPTZ NOT NULL
);
