-- Widget access token becomes a per-user identity token instead of a
-- twitchat-only concept, so future widget-scoped API routes (e.g. game/IGDB/
-- Steam lookups) can authenticate with the same token without depending on
-- the twitchat feature. Existing tokens are preserved so URLs streamers
-- already pasted into OBS/Twitchat keep working.
ALTER TABLE user_account ADD COLUMN widget_token UUID;

UPDATE user_account u
SET widget_token = t.widget_token
FROM twitchat_widget_settings t
WHERE t.user_id = u.id;

ALTER TABLE user_account ADD CONSTRAINT uk_user_account_widget_token UNIQUE (widget_token);

ALTER TABLE twitchat_widget_settings DROP COLUMN widget_token;
