Hypixel / Mojang fixtures (lot 4, PR 7).

Recorded live on 2026-11-23, no key needed:
- games.json: GET https://api.hypixel.net/v2/resources/games (200, success true, 30 games; Cloudflare cached).
- mojang-profile.json: GET https://api.mojang.com/users/profiles/minecraft/Notch (200 {id, name}; pretty-printed
  by Mojang, compacted here). Headers seen: cache-control max-age=300, x-minecraft-rate-limit-result UNDER_LIMIT.
- mojang-404.json: GET https://api.mojang.com/users/profiles/minecraft/zz_no_such_user_zz9 (404 JSON {path,
  errorMessage}). A name with a space or "!" also answers that 404 (checked).
  Also seen: sessionserver.mojang.com/session/minecraft/profile/<unknown uuid> answers 204 with no body (not used).

From the spec (live on 2026-11-22): missing-key.json, GET /v2/player without the API-Key header (400).

NOT checked live (no Hypixel key; the keyed routes were not called): player.json, player-hidden.json,
player-new.json, player-null.json, status.json, status-offline.json, invalid-key.json, throttle.json. They follow the
OpenAPI document embedded in https://api.hypixel.net/ (read on 2026-11-23: API-Key header, 400 DataMissing, 403
"Invalid API key", 422 "Malformed UUID", 429 "Key throttle" with throttle / global, RateLimit-Limit /
RateLimit-Remaining / RateLimit-Reset headers, session {online, gameType, mode, map}) and the widely known shape of
the player document (networkExp, karma, achievementPoints, achievements.bedwars_level, stats.Bedwars.*_bedwars,
stats.SkyWars.levelFormatted / wins / losses / kills / deaths; lastLogin absent when the player hides their online
status; {"success":true,"player":null} for a UUID that never joined). Re-record them with a real key before
trusting the Bed Wars / SkyWars field names.
