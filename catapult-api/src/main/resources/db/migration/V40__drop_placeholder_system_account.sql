-- The previous SystemAccountInitializer created an empty UserAccount with
-- systemAccount=true at startup as a "settings template" for new users.
-- The new model is: a regular user signs up (the bot) and an admin promotes
-- the account via /api/admin/members/{id}/promote-to-system. The initializer
-- is removed; any leftover placeholder is dropped here.
--
-- We intentionally only target rows that have no Twitch identity — a system
-- account with a Twitch id is a real promoted bot and must be preserved.
DELETE FROM user_account
WHERE is_system = TRUE
  AND twitch_id IS NULL;
