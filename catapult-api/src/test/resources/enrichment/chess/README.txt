Real answers of the public Chess.com and Lichess APIs, recorded live on 2026-11-23 (no key, Catapult User-Agent),
kept as they came except for being split one per file:

- chesscom-player.json          GET https://api.chess.com/pub/player/hikaru                  200
- chesscom-stats.json           GET https://api.chess.com/pub/player/hikaru/stats            200
- chesscom-stats-unrated.json   GET https://api.chess.com/pub/player/foo/stats               200 (no rated game)
- chesscom-games.json           GET https://api.chess.com/pub/player/erik/games              200 (5 daily games)
- chesscom-games-empty.json     GET https://api.chess.com/pub/player/hikaru/games            200 (none)
- chesscom-404.json             GET https://api.chess.com/pub/player/zzzznotauserqq123       404 JSON
- lichess-user.json             GET https://lichess.org/api/user/DrNykterstein               200 (not playing)
- lichess-user-playing.json     GET https://lichess.org/api/user/novik70                     200 (playing: a game URL)
- lichess-user-disabled.json    GET https://lichess.org/api/user/Thibault2                   200 (closed account)
- lichess-404.json              GET https://lichess.org/api/user/zzzznotauserqq123           404 JSON

Also seen live that day, not kept as files: Chess.com answers a name with capitals by a 301 to the lowercase one
(so the client lowercases it: redirects are never followed), a name with a space by 410 text/plain, and sends
cache-control max-age=5 (found) or 30 (404). Lichess answers its 404 as HTML unless asked for JSON
(Accept: application/json), and sends no rate-limit header. Neither API's documentation page was checked here.
