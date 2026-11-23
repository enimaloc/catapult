These fixtures were built on 2026-11-23 without any Riot API key (development keys expire every 24 h, a production
key needs an approved application): they follow the shapes documented on developer.riotgames.com and are NOT
verified live. To be recorded again with a real key (a enregistrer a nouveau):
  account.json (account-v1 by-riot-id), account-404.json, lol-summoner.json (summoner-v4 by-puuid),
  lol-league.json (league-v4 entries/by-puuid), lol-active-game.json and lol-active-game-404.json
  (spectator-v5 active-games/by-summoner/{puuid}), tft-league.json (tft league-v1 by-puuid).
Seen live, the same day: unauthorized.json (account-v1 without a key answers 401 with this body, checked
2026-11-22), and Data Dragon, which needs no key: ddragon-versions.json (the first three entries of
https://ddragon.leagueoflegends.com/api/versions.json) and ddragon-champions.json (three champions of
/cdn/16.23.1/data/en_US/champion.json, otherwise as served).
