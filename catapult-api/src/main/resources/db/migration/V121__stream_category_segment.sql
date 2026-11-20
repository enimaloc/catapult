-- The timeline of a member's live: one row per period spent on a (Twitch category, detected game) pair.
-- category_id/category_name are null while the category is unknown; game_name is null when no game is detected;
-- ended_at is null for the period still running.
CREATE TABLE stream_category_segment (
    id            UUID PRIMARY KEY,
    user_id       UUID NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    stream_id     VARCHAR(64) NOT NULL,
    category_id   VARCHAR(64),
    category_name VARCHAR(255),
    game_name     VARCHAR(255),
    started_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at      TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_stream_category_segment_user_stream ON stream_category_segment (user_id, stream_id);

-- At most one running period per member, whichever site writes it.
CREATE UNIQUE INDEX idx_stream_category_segment_open ON stream_category_segment (user_id) WHERE ended_at IS NULL;
