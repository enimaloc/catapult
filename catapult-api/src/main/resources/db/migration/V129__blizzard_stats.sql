-- A member's Blizzard account: the region their Battle.net account plays on and, once their World of Warcraft
-- characters were imported (one OAuth read, the user token is never kept), the list of those characters and the one
-- picked as their main. No secret is stored. Unlinking Battle.net or deleting the member's account deletes the rows.
-- `version` (optimistic locking) keeps two settings writes from overwriting each other silently.
CREATE TABLE blizzard_account (
    user_id               UUID        PRIMARY KEY REFERENCES user_account (id) ON DELETE CASCADE,
    region                TEXT        NOT NULL,
    wow_main_region       TEXT,
    wow_main_character_id BIGINT,
    wow_imported_at       TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ NOT NULL,
    version               BIGINT      NOT NULL DEFAULT 0
);

CREATE TABLE blizzard_wow_character (
    user_id      UUID    NOT NULL REFERENCES blizzard_account (user_id) ON DELETE CASCADE,
    region       TEXT    NOT NULL,
    character_id BIGINT  NOT NULL,
    realm_slug   TEXT    NOT NULL,
    realm_name   TEXT    NOT NULL,
    name         TEXT    NOT NULL,
    display_name TEXT    NOT NULL,
    level        INTEGER NOT NULL,
    class_name   TEXT    NOT NULL,
    faction      TEXT    NOT NULL,
    PRIMARY KEY (user_id, region, character_id)
);
