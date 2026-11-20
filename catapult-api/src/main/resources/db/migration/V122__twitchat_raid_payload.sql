-- V122__twitchat_raid_payload.sql
-- Gives the accounts whose Twitchat event actions were set up before RAIDED_BY_MEMBER existed its default payload,
-- once: a member who later removes it does not get it back. Accounts set up afterwards get it with the other
-- defaults (TwitchatEventDefaults.SEEDED). data_json is exactly TwitchatEventDefaults.chatMessage(RAIDED_BY_MEMBER)
-- serialized (checked by TwitchatRaidPayloadMigrationTest).
INSERT INTO twitchat_event_action (id, user_id, event_type, position, action, mode, data_json, enabled, created_at,
                                   updated_at)
SELECT gen_random_uuid(), s.user_id, 'RAIDED_BY_MEMBER', 0, 'CUSTOM_CHAT_MESSAGE', 'FORM',
       '{"message":"Pendant sa session de stream, {{raider}} a joué à :\n{{games}}","style":"message","icon":"raid","user":{"name":"Catapult"},"actions":[]}',
       TRUE, now(), now()
FROM twitchat_widget_settings s
WHERE s.event_actions_initialized = TRUE
  AND NOT EXISTS (SELECT 1 FROM twitchat_event_action a
                  WHERE a.user_id = s.user_id AND a.event_type = 'RAIDED_BY_MEMBER');
