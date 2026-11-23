# osu! fixtures

Built from the documented shapes of the osu! API v2 (osu.ppy.sh/docs, read on 2026-11-23: "Authorization Code
Grant", "Refresh access token", "Get Own Data", "Get User Scores", the `User`, `UserExtended`, `UserStatistics` and
`Score` objects, "API Response Version"). **Nothing here was verified live**: no osu! OAuth application (client id,
secret) nor member token was available, and the authenticated endpoints were not called without one.

| fixture | source | checked live |
|---|---|---|
| `token.json` | the documented token answer (`access_token`, `expires_in` 86400, `refresh_token`, `token_type`) | no |
| `token-invalid-grant.json` | a refused refresh token, as Laravel Passport (league/oauth2-server 8+) words it | no |
| `token-invalid-request.json` | the same refusal as older league/oauth2-server versions word it | no |
| `token-invalid-client.json` | a refused client (wrong secret): the application's failure, never the member's | no |
| `me.json` | `GET /api/v2/me` (UserExtended + `statistics` + `statistics_rulesets`), trimmed, values invented | no |
| `scores-recent.json` | `GET /api/v2/users/{id}/scores/recent?include_fails=0` with `x-api-version: 20220705` (lazer score format: `ruleset_id`, `ended_at`, accuracy in 0..1, mods as objects), with `beatmap` and `beatmapset` | no |
| `unauthenticated.json` | the 401 body osu! answers a missing or expired token with (from memory of the API, not from the docs) | no |
| `not-json.html` | an HTML page answered with a 2xx (maintenance, captive portal) | n/a |

Re-record them from real answers once an application is set (spec: "Avant merge … réponses réelles réenregistrées").
In particular, check: the error body of a refused refresh token (`invalid_grant` vs `invalid_request`), whether
`statistics.country_rank` is in `/me`, and whether `play_time` can be null.
