# Catapult

[![Tests](https://github.com/enimaloc/catapult/actions/workflows/test.yml/badge.svg)](https://github.com/enimaloc/catapult/actions/workflows/test.yml)
[![Build](https://github.com/enimaloc/catapult/actions/workflows/build.yml/badge.svg)](https://github.com/enimaloc/catapult/actions/workflows/build.yml)

Outil d'automatisation pour streamers Twitch : détecte le jeu en cours et met à jour automatiquement la catégorie Twitch ainsi que les labels de classification de contenu (CCL) selon les données IGDB.

## Fonctionnalités

- **Détection automatique du jeu** via Steam, Xbox et Battle.net (chaîne de priorité configurable)
- **Mise à jour Twitch** — catégorie et labels CCL synchronisés à chaque changement de jeu
- **Commandes chat data-driven** — presets `!game`, `!description`, `!store`, `!release`, `!igdb`, `!triggers` (templates personnalisables avec placeholders `{game.name|fallback}`, `{dtdd.yes|no|mostly}`, `{game.agerating}`), `!setgame` côté modération. Réponses postées par un compte bot dédié quand il est `/mod` du canal, sinon fallback transparent sur le compte du streamer. Conditionnées par l'experiment `chat.commands` (rolling release).
- **Trigger warnings DoesTheDogDie** — intégration optionnelle de [doesthedogdie.com](https://www.doesthedogdie.com) avec pool de clés API rotatif, cache à 3 niveaux, mapping IGDB→DTDD revu par streamer/admin, et placeholders `{dtdd.*}` pour les chat commands.
- **Interface d'administration** — gestion des bindings jeu, des règles CCL et des paramètres utilisateur
- **A/B testing** — moteur d'expérimentation interne, avec support optionnel Unleash, GrowthBook et GitLab Feature Flags
- **Métriques Prometheus** exposées sur `/actuator/prometheus`

## Démarrage rapide

### Prérequis

- Docker et Docker Compose
- Une application Twitch enregistrée sur [dev.twitch.tv](https://dev.twitch.tv/console/apps)

### Installation

```bash
cp .env.example .env
# Éditer .env avec vos credentials (voir section Configuration)

docker compose up -d
```

L'application est accessible sur `http://localhost` (port choisi par `WEB_PORTS`, 80 par défaut, sur la boucle locale).

### Déploiement

`catapult-web` est le seul point d'entrée : il sert le site et relaie lui-même vers `catapult-api` les chemins
que le navigateur appelle directement (login Twitch `/oauth2/` et `/login/oauth2/`, retour Steam, `/api/game/`,
`/api/user/`, Swagger). Quand `catapult-api` est injoignable, ces chemins répondent la page de maintenance (503).
`catapult-api` n'est jamais exposé.

Le proxy TLS placé devant (Caddy, Traefik, Cloudflare…) doit :

- terminer le TLS et parler **HTTP/2** au navigateur (chaque onglet garde des flux SSE ouverts, que HTTP/1.1
  limite à 6 connexions par hôte) ;
- transmettre `X-Forwarded-For`, `X-Forwarded-Proto` et `X-Forwarded-Host` en **remplaçant** ceux du visiteur
  (les adresses de retour Twitch et Steam en dépendent, et `catapult-web` croit ceux d'une adresse privée) ;
- être le seul à joindre `catapult-web` : le port est publié sur `127.0.0.1` (`WEB_BIND`) ; ne l'ouvrir
  (`WEB_BIND=0.0.0.0`) que si le proxy est sur une autre machine, derrière un pare-feu qui ne laisse passer que lui ;
- ne pas mettre en tampon les réponses `text/event-stream`, et tolérer des réponses de plusieurs minutes
  (`catapult-web` envoie un battement toutes les 30 s) ;
- sonder `/up` pour la santé ;
- avec plusieurs instances (`CATAPULT_WEB_REPLICAS=N`, `WEB_PORTS=8081-808N`), répartir entre elles : sessions et
  événements passent par Redis, n'importe quelle instance sert n'importe quel visiteur.

## Configuration

| Variable | Obligatoire | Description |
|---|---|---|
| `DB_USERNAME` | Oui | Utilisateur PostgreSQL |
| `DB_PASSWORD` | Oui | Mot de passe PostgreSQL |
| `TOKEN_ENCRYPTION_KEY` | Oui | Clé AES-256 pour les tokens OAuth (`openssl rand -base64 32`) |
| `TWITCH_CLIENT_ID` | Oui | Client ID de votre application Twitch |
| `TWITCH_CLIENT_SECRET` | Oui | Client Secret Twitch |
| `STEAM_API_KEY` | Non | Clé API Steam (détection de jeu via Steam) |
| `XBOX_CLIENT_ID` | Non | Client ID Azure (détection via Xbox) |
| `XBOX_CLIENT_SECRET` | Non | Client Secret Azure |
| `BATTLENET_CLIENT_ID` | Non | Client ID Battle.net |
| `BATTLENET_CLIENT_SECRET` | Non | Client Secret Battle.net |
| `IGDB_CLIENT_ID` | Non | Client ID IGDB (utilise `TWITCH_CLIENT_ID` si absent) |
| `DTDD_ENABLED` | Non | Active l'intégration DoesTheDogDie (clés gérées dans l'admin `/admin/dtdd-keys`) |

> Le compte bot Twitch (utilisé pour répondre aux commandes chat) se configure depuis l'admin : page Membres → "Lier le Bot Twitch" sur le compte système (qui démarre un flow OAuth Twitch dédié, le token est stocké chiffré en base). Tant que cette liaison n'est pas faite, les réponses passent par le compte du streamer.

## Développement

### Prérequis

- Java 21+
- PostgreSQL 16+

### Build et tests

```bash
# Compiler
./gradlew build

# Tests (génère aussi le rapport de couverture dans build/reports/jacoco/)
./gradlew test

# Lancer localement (nécessite un .env configuré)
./gradlew bootRun
```

### Stack technique

| Couche | Technologie |
|---|---|
| Framework | Spring Boot 4.0.4 |
| Persistance | PostgreSQL 16, JPA/Hibernate, Flyway |
| Frontend | Thymeleaf, HTMX, Chart.js |
| Sécurité | Spring Security, OAuth2 |
| Monitoring | Micrometer, Prometheus |
| Tests | JUnit 5, JaCoCo |
| Qualité | SonarQube |

### Structure du projet

```
src/main/java/fr/enimaloc/catapult/
├── chat/          Commandes chat Twitch
├── config/        Configuration Spring (OAuth2, expérimentations…)
├── domain/        Entités JPA
├── event/         Événements applicatifs (détection de jeu, logs…)
├── experiment/    Moteur A/B testing et providers
├── getter/        Récupération du jeu en cours (Steam, Xbox, Battle.net)
├── repository/    Accès base de données
├── security/      Configuration Spring Security
├── service/       Logique métier
└── web/           Contrôleurs HTTP et templates
```
