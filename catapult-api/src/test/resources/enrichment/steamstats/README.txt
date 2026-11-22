global-percentages-440.json is a real answer (recorded 2026-11-22, truncated to 5 entries, percent as strings).
Checked live on 2026-11-22 with a pool key: owned-games-private.json ({"response":{}}) and
player-achievements-private.json (403, "Profile is not public") match the real answers; a wrong key gets a 403 HTML
page ("verify your key= parameter"); GetSchemaForGame for an app without achievements answers {"game":{}}.
Still built from the documentation (no public profile at hand): owned-games.json, player-achievements.json,
player-achievements-nostats.json, schema.json.
