-- A member's chess accounts: the Chess.com name they declared with the stable player id Chess.com proved for it,
-- and the Lichess account they declared (its stable lowercase id and the name as written). Each site is optional;
-- the row exists only while it holds at least one of them. No secret is stored (both APIs are public). Deleting
-- the member's account deletes the row. `version` (optimistic locking) keeps two settings writes from overwriting
-- each other silently.
CREATE TABLE chess_account (
    user_id            UUID        PRIMARY KEY REFERENCES user_account (id) ON DELETE CASCADE,
    chesscom_username  TEXT,
    chesscom_player_id BIGINT,
    lichess_id         TEXT,
    lichess_username   TEXT,
    updated_at         TIMESTAMPTZ NOT NULL,
    version            BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chess_account_chesscom_whole CHECK ((chesscom_username IS NULL) = (chesscom_player_id IS NULL)),
    CONSTRAINT chess_account_lichess_whole CHECK ((lichess_id IS NULL) = (lichess_username IS NULL))
);
