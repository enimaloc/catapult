-- The Twitchat public API the streamer's pages speak: 'auto' detects it from Twitchat,
-- 'stable' (Twitchat's main branch) or 'beta' force it (catapult-web's twitchat.js).
ALTER TABLE twitchat_widget_settings ADD COLUMN twitchat_branch VARCHAR(16) NOT NULL DEFAULT 'auto';
