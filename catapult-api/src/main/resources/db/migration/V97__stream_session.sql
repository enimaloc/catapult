-- The last stream handled for each member, so a site taking over can tell which stream starts and ends were missed.
CREATE TABLE stream_session (
    user_id    UUID PRIMARY KEY REFERENCES user_account (id) ON DELETE CASCADE,
    stream_id  VARCHAR(64) NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at   TIMESTAMP WITH TIME ZONE
);
