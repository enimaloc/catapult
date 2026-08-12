-- Payload preset "actions" moved from a label/theme override map to a raw JSON array (with
-- {{action:TYPE}} token placeholders), giving full control over the actions payload sent to
-- Twitchat. Nothing is in production yet, so existing presets are cleared rather than migrated.
DELETE FROM twitchat_active_preset;
DELETE FROM twitchat_payload_preset;
