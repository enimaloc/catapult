Supercell API fixtures (Clash of Clans, Clash Royale, Brawl Stars).

NOT verified live: no Supercell key was available when they were written (keys are bound to the IP addresses they
were created for). They follow the shapes Supercell's developer portals document
(developer.clashofclans.com, developer.clashroyale.com, developer.brawlstars.com), cut down to the fields the
enrichment reads. Only the keyless answer was checked live (2026-11-22):
  GET https://api.clashofclans.com/v1/players/%232PP -> 403 {"reason":"accessDenied","message":"Missing authorization"}

- coc-player.json, cr-player.json, bs-player.json: GET /v1/players/{tag} of each game.
- not-found.json: the 404 of an unknown tag (reason notFound).
- invalid-ip.json: the 403 of a key used from an address it is not bound to (reason accessDenied.invalidIp).
- access-denied.json: the 403 of a missing or invalid key (reason accessDenied).
- throttled.json: the 429 (reason requestThrottled).
- maintenance.json: the 503 during a maintenance break (reason inMaintenance).

Re-record them from real answers once a key exists (the IP addresses in invalid-ip.json are documentation ones).
