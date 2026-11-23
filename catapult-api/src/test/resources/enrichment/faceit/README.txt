These fixtures were built on 2026-11-23 without any FACEIT API key (the owner adds one to the admin pool later):
they follow the shapes of FACEIT's public Data API v4 swagger (https://open.faceit.com/data/v4/docs/swagger.json,
read that day) and are NOT verified live. The swagger types the lifetime and per-match stats as free-form maps: the
key names used here ("Matches", "Win Rate %", "Average K/D Ratio", "Map", "Score", "Result", "Match Finished At"...)
are the ones commonly seen in FACEIT answers, not checked on a real one. The 404 body is the error shape seen on the
403 below with http_status 404, not checked either. To be recorded again with a real key (a enregistrer a nouveau):
  player.json (GET /players?nickname=), player-no-cs2.json, player-404.json, lifetime.json
  (GET /players/{id}/stats/cs2), last-match.json and last-match-empty.json
  (GET /players/{id}/games/cs2/stats?offset=0&limit=1).
Seen live on 2026-11-22: forbidden.json (GET /players?nickname=s1mple without a key answers 403 with this body and
ratelimit-limit: 20, 20;w=1).
