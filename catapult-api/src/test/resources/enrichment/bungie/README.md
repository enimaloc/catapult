# Bungie.net fixtures

Built from Bungie's documented shapes (bungie.net/Platform API docs, the OAuth wiki), **not verified live** except
where noted: no Bungie application (API key, OAuth client) was available when they were written.

| fixture | source | checked live |
|---|---|---|
| `settings.json` | `GET /Platform/Settings/` (no key needed), trimmed | yes, 2026-11-23 |
| `season-definition.json` | the manifest's `DestinySeasonDefinition` for the current season, read from its public JSON file and wrapped in the `{Response, ErrorCode…}` envelope the definition route answers with | definition yes (2026-11-23), envelope of `/Destiny2/Manifest/{entity}/{hash}/` no (needs a key) |
| `error-*.json` | the envelope and error codes (`1601` DestinyAccountNotFound, `1665` DestinyPrivacyRestriction, `36` throttle, `5` SystemDisabled, `99` WebAuthRequired) | the envelope and `99` / `2102` yes (answers without a key or token), the others no |
| `token.json`, `token-invalid-grant.json` | the OAuth wiki's token answer (`refresh_expires_in`, `membership_id`) | no |
| `memberships.json` | `GET /User/GetMembershipsForCurrentUser/` | no |
| `profile.json`, `profile-private.json` | `GET /Destiny2/{type}/Profile/{id}/?components=100,200,204` | no |
| `records.json` | `GET /Destiny2/{type}/Profile/{id}/?components=900`, trimmed | no |
| `activity-definition.json` | `GET /Destiny2/Manifest/DestinyActivityDefinition/{hash}/` | no |

Re-record them from real answers once an application is set (spec: "Avant merge … réponses réelles réenregistrées").
