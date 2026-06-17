-- V35 — ensure alpha_invite tables exist (repair for environments where V34 ran with stale content)

CREATE TABLE IF NOT EXISTS alpha_invite (
    id              UUID         NOT NULL PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id        UUID         NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    code            VARCHAR(12)  NOT NULL UNIQUE,
    max_uses        INTEGER,
    use_count       INTEGER      NOT NULL DEFAULT 0,
    can_reinvite    BOOLEAN,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    regenerated_at  TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_alpha_invite_owner ON alpha_invite(owner_id);
CREATE INDEX        IF NOT EXISTS idx_alpha_invite_code  ON alpha_invite(code);

CREATE TABLE IF NOT EXISTS alpha_invite_redemption (
    id                  UUID        NOT NULL PRIMARY KEY DEFAULT gen_random_uuid(),
    invite_id           UUID        NOT NULL REFERENCES alpha_invite(id) ON DELETE CASCADE,
    invitee_twitch_id   VARCHAR(50) NOT NULL,
    redeemed_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_invite_redemption_invite   ON alpha_invite_redemption(invite_id);
CREATE INDEX IF NOT EXISTS idx_invite_redemption_invitee  ON alpha_invite_redemption(invitee_twitch_id);
