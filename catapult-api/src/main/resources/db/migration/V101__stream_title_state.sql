-- What Catapult knows of each channel's title, for {{ORIGINAL_TITLE}}: the title the streamer set themselves (the
-- one found before Catapult's first change, or after they changed it by hand), and the last one Catapult sent.
CREATE TABLE stream_title_state (
    user_id         UUID PRIMARY KEY REFERENCES user_account (id) ON DELETE CASCADE,
    original_title  VARCHAR(140),
    last_sent_title VARCHAR(140)
);
