-- Supercell (lot 4): Clash of Clans, Clash Royale and Brawl Stars. Each game has its own API and its own developer
-- portal, so each key the admin shares belongs to one game (`game`: clash-of-clans, clash-royale or brawl-stars);
-- Supercell keys are JWTs bound to the IP addresses they were created for. And a member's declared player tag per
-- game (`#` and the tag's characters, as Supercell spells it) with the player name Supercell gave for it. No secret is
-- stored with the account. Deleting the member's account deletes the row. `version` (optimistic locking) keeps two
-- settings writes from overwriting each other silently.
CREATE TABLE supercell_api_key (
    api_key      TEXT    NOT NULL,
    game         TEXT    NOT NULL,
    owner        UUID    NULL REFERENCES user_account (id) ON DELETE CASCADE,
    is_exclusive BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (api_key)
);

CREATE TABLE supercell_account (
    user_id   UUID        PRIMARY KEY REFERENCES user_account (id) ON DELETE CASCADE,
    coc_tag   TEXT        NULL,
    coc_name  TEXT        NULL,
    cr_tag    TEXT        NULL,
    cr_name   TEXT        NULL,
    bs_tag    TEXT        NULL,
    bs_name   TEXT        NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version   BIGINT      NOT NULL DEFAULT 0
);
