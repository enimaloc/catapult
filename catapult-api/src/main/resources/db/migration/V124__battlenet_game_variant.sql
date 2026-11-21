-- Admin rules that turn a Battle.net game into a variant of its own (source_id '<fourcc>:<variant_key>', e.g.
-- 'WoW:Forever') when the presence's activity text (field (1, 25)) matches one of the patterns.
-- Rollout of a rule: create the game mapping of its source_id first, then enable the rule, so that no streamer
-- loses their Twitch category in between.
CREATE TABLE battlenet_game_variant (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    fourcc        TEXT        NOT NULL,
    variant_key   TEXT        NOT NULL,
    match_type    TEXT        NOT NULL,
    patterns_json JSONB       NOT NULL,
    display_name  TEXT        NOT NULL,
    enabled       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by    UUID,
    UNIQUE (fourcc, variant_key)
);

-- WoW: Forever, disabled until its game mapping exists
INSERT INTO battlenet_game_variant (fourcc, variant_key, match_type, patterns_json, display_name)
VALUES ('WoW', 'Forever', 'PREFIX', '["Forever - "]', 'World of Warcraft: Forever');
