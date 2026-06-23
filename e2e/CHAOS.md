# Catapult — Chaos checklist (manuel, pré-release)

Scénarios de résilience à exécuter à la main avant chaque release de
`feat/server-event-stream`. Ils complètent la suite Playwright qui
couvre les scénarios « normaux ».

Lance la stack en local :

```bash
docker compose up -d
docker compose ps     # tous "healthy"
```

Garde un onglet ouvert sur `http://localhost/` avec la console devtools.

---

## 1. Coupure Redis

**But** : vérifier que le hub WS reste vivant et que seuls les broadcasts
en attente sont perdus (les sessions connectées continuent de recevoir les
pings).

```bash
docker compose kill catapult-redis
```

- [ ] L'onglet : pas d'overlay (le WS local reste up)
- [ ] Devtools → Network/WS : pings 15s toujours reçus
- [ ] Tentative de broadcast admin → erreur côté API (Redis indisponible)
- [ ] `docker compose start catapult-redis` → broadcasts reprennent sans
      reload de l'onglet

## 2. Coupure API

**But** : les notifs s'arrêtent (l'API ne publie plus), la recherche live
remonte `BACKEND_UNAVAILABLE`, le reste de l'UI tient.

```bash
docker compose kill catapult-api
```

- [ ] Onglet : pas d'overlay (catapult-web et le WS restent live)
- [ ] Une recherche Twitch ou DTDD via WS → response `ok:false`,
      code `BACKEND_UNAVAILABLE` ou status 5xx
- [ ] Pas de notif push même si un évènement aurait dû en générer
- [ ] `docker compose start catapult-api` → recherches reprennent

## 3. Coupure brutale catapult-web

**But** : l'overlay « degraded » apparaît dans ~45s, la reco est
automatique au redémarrage.

```bash
docker compose kill catapult-web
```

- [ ] Onglet : overlay « Connexion perdue » dans **< 50s** (45s watchdog +
      petit délai de bascule)
- [ ] `inert` appliqué sur `<main>` → impossible de cliquer sur les liens
- [ ] `docker compose start catapult-web` → overlay disparaît, toast
      « Connexion rétablie » 3s
- [ ] Formulaires non-soumis sont préservés (pas de reload)

## 4. Shutdown gracieux (SIGTERM)

**But** : le hook `GracefulShutdownBroadcaster` diffuse
`maintenance.imminent` avant l'exit. L'overlay doit afficher la variante
« shutdown » (ambre, countdown), pas « degraded ».

```bash
docker compose stop catapult-web   # SIGTERM, grace 10s
```

- [ ] Overlay « Redémarrage en cours » avec countdown **dans la seconde**
- [ ] La fenêtre sticky de 10s empêche un flash « degraded » si la perte
      de ping arrive avant la réception du frame
- [ ] `docker compose start catapult-web` → overlay disparaît

## 5. Latence réseau 5s (no faux-positif)

**But** : un réseau lent ne doit pas déclencher l'overlay tant qu'au moins
un ping passe dans la fenêtre 45s.

```bash
# Sur Linux avec tc-netem :
docker compose exec --user root catapult-router \
  tc qdisc add dev eth0 root netem delay 5000ms
```

- [ ] Onglet : pas d'overlay (les pings 15s arrivent toujours avant 45s)
- [ ] Une recherche reste fonctionnelle mais lente
- [ ] Cleanup :

  ```bash
  docker compose exec --user root catapult-router \
    tc qdisc del dev eth0 root netem
  ```

## 6. 100 onglets, même user

**But** : pas de fuite mémoire dans `WsSessionRegistry`, broadcasts
fan-outés à tous les onglets.

- [ ] Ouvrir 100 onglets via un script (`for i in {1..100}; do
      xdg-open http://localhost/channels/USER ; done`)
- [ ] `docker compose exec catapult-web jcmd 1 GC.heap_info` → pas de
      pic anormal
- [ ] Publier un broadcast `events.global` → tous les onglets le
      reçoivent (vérifier 5-10 au hasard)
- [ ] Fermer les 100 onglets → la map `sessionsById` revient à 0
      (exposer via actuator/metrics si nécessaire)

## 7. Spam broadcast 100×

**But** : le rate limit admin (10/min) rejette les excédents avec 429.

```bash
for i in $(seq 1 100); do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -X POST http://localhost/api/admin/broadcast \
    -H 'Content-Type: application/json' \
    -b "JSESSIONID=$ADMIN_COOKIE" \
    -d '{"channel":"events.global","name":"alert.info",
         "data":{"title":"spam","body":"x","ttlSeconds":10}}'
done | sort | uniq -c
```

- [ ] Distribution attendue : ~10 × 202, ~90 × 429
- [ ] Onglet client : 10 toasts (pas 100)

## 8. XSS via broadcast `data`

**But** : confirmer que le payload `data` est rendu via `textContent` côté
client, pas `innerHTML`.

```bash
curl -X POST http://localhost/api/admin/broadcast \
  -H 'Content-Type: application/json' \
  -b "JSESSIONID=$ADMIN_COOKIE" \
  -d '{"channel":"events.global","name":"alert.info",
       "data":{"title":"<script>alert(1)</script>",
               "body":"<img src=x onerror=alert(2)>","ttlSeconds":10}}'
```

- [ ] Toast affiché → texte brut visible, **aucun** `alert()` ne se
      déclenche
- [ ] Devtools → Elements : aucun `<script>` ni handler `onerror`
      injecté dans le DOM

---

## Cleanup général

```bash
docker compose down
docker compose up -d
```

Tous les checks doivent passer avant `glab mr update --ready`.
