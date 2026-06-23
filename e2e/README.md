# Catapult E2E (Playwright)

End-to-end tests covering the WebSocket stack introduced by
`feat/server-event-stream`: overlay state machine, preventive banner,
shutdown countdown, live search RPC and (optionally) notifications.

## Prerequisites

1. Node.js 20+ on the host running the tests.
2. The docker compose stack up and reachable on `BASE_URL`
   (default `http://localhost`, set via the `ROUTER_PORT` compose var).
3. Docker access from the host (for scenarios that stop / start
   `catapult-web` or publish to Redis).

## One-time setup

```bash
cd e2e
npm ci
npx playwright install --with-deps chromium
```

The browser download is ~150 MB on first run. `--with-deps` pulls the
system libraries required to run headless Chromium (sudo prompt on
Linux).

## Running

```bash
# from the project root
docker compose up -d

cd e2e
npx playwright test                # full suite
npx playwright test --headed       # see the browser
npx playwright test maintenance-overlay-degraded.spec.ts
```

Reports:

```bash
npx playwright show-report
```

## Scenario coverage

| # | File | Scenario | Auth | Docker mutation |
|---|---|---|---|---|
| 1 | `notification-realtime.spec.ts`         | Notif reçue en temps réel                | ✓ | none |
| 2 | `maintenance-overlay-degraded.spec.ts`  | Overlay sur perte de ping (kill web)     | × | stop  catapult-web |
| 3 | `maintenance-overlay-reconnect.spec.ts` | Overlay disparaît + toast à la reco      | × | stop/start catapult-web |
| 4 | `maintenance-banner-scheduled.spec.ts`  | Bannière pour `maintenance.scheduled`    | × | redis publish |
| 5 | `search-twitch-live.spec.ts`            | Recherche live `search.dtdd` via WS      | × | none |
| 6 | `notification-ack.spec.ts`              | Ack notif via `command notification.read`| ✓ | none |
| 7 | `shutdown-imminent.spec.ts`             | SIGTERM web → overlay shutdown + countdown | × | stop catapult-web |

## Auth-required scenarios (1 and 6)

`catapult` only supports Twitch OAuth2 login. There is no dev-login
endpoint, and Twitch refuses the OAuth flow inside Playwright (third-party
cookies / human verification). To run these specs locally:

1. Log into `http://localhost` via Twitch in your normal browser.
2. Copy the `JSESSIONID` cookie value from devtools (Application → Cookies).
3. Export it before running the suite:

   ```bash
   export CATAPULT_E2E_AUTH_COOKIE='node02...'
   npx playwright test notification-realtime.spec.ts
   ```

Without `CATAPULT_E2E_AUTH_COOKIE`, both scenarios are skipped — the suite
still runs and the report shows them as skipped, not failed.

## Skipping docker-dependent scenarios

Tests 2, 3, 4 and 7 mutate the running compose stack (`docker compose
stop/start/exec`). On CI without docker:

```bash
export CATAPULT_E2E_SKIP_DOCKER=1
npx playwright test
```

They will be skipped instead of failing.

## Troubleshooting

- **`docker compose ... failed`**: confirm you are in the repo root and
  the `catapult-web` / `catapult-redis` services are running
  (`docker compose ps`).
- **Overlay never appears**: check the WS handshake reached
  `catapult-web` (`docker compose logs catapult-web | grep WsHub`) and
  that nginx-router has the `/ws` upgrade block (see
  `nginx-router/nginx.conf.template`).
- **Tests time out around scenario 3**: bumping the `waitForHealthy`
  budget helps on slower hosts — edit `e2e/tests/helpers/compose.ts`.

## CI considerations

The current suite is intended to run **locally** before mark-ready /
merge. CI integration requires a docker-in-docker runner and is tracked
separately; see `e2e/CHAOS.md` for the manual pre-release runs that
complement this suite.
