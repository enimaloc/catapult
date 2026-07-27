-- SenderRole gained FOLLOWERS/SUBS/VIP tiers between the old EVERYONE and MODERATOR, and
-- EVERYONE was renamed to VIEWERS (same meaning, clearer name alongside the new tiers) —
-- existing rows still say the old literal and must be rewritten to keep resolving to a
-- valid enum constant.
UPDATE chat_command_definition SET permission = 'VIEWERS' WHERE permission = 'EVERYONE';
