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

### Serveur de secours (mode dégradé)

Pour faire tourner catapult sur un serveur de secours moins puissant, activer le profil `degraded` sur
`catapult-api` et `catapult-web` :

```bash
SPRING_PROFILES_ACTIVE=degraded
INSTATUS_PAGE_URL=https://votre-page.instatus.com   # page de statut Instatus, optionnelle
CATAPULT_WEB_REPLICAS=1                             # une seule instance de catapult-web
```

`docker-compose.yml` transmet `SPRING_PROFILES_ACTIVE` et `INSTATUS_PAGE_URL` aux conteneurs (vides par défaut :
aucun profil, pas de lien).

Le profil espace les boucles de fond (détection de jeu toutes les 60 s, synchronisation des amis Minecraft
toutes les 15 min, relance des données incomplètes et sondage des retours moins fréquents) et plafonne
l'échantillonnage des traces Sentry à 10 % (clé `catapult.degraded.traces-sample-rate`, qui s'applique même si
`SENTRY_TRACES_SAMPLE_RATE` est définie). Ses valeurs l'emportent sur les surcharges de configuration de l'admin. `catapult-web`
affiche alors un bandeau repliable ; avec `INSTATUS_PAGE_URL`, il renvoie aussi vers l'incident en cours sur la page
de statut. `GET /api/status/degraded` indique l'état courant et les propriétés que le profil modifie, chacune avec sa valeur normale ;
il n'est joignable que depuis le réseau interne (`catapult-api` n'est jamais exposée, `catapult-web` l'appelle).
Une propriété ajoutée au profil `degraded` de `catapult-api` apparaît dans le bandeau dès qu'elle a un libellé
`degraded.effect.<clé>` (FR et EN, dans `catapult-web/src/main/resources/lang/`), et seulement si sa valeur diffère
de sa valeur normale. Une ligne est aussi masquée quand `<premier segment de la clé>.enabled=false` (par exemple
`minecraft.enabled` pour `minecraft.*`). La clé doit aussi être présente dans l'`application.properties` de
`catapult-api` : une valeur normale qui n'existe que comme défaut dans le code (`${x:5}`) ne peut pas être comparée,
la ligne n'apparaît jamais et un avertissement est journalisé au démarrage.

### Mode débug d'un membre

Pour comprendre un problème qu'on ne reproduit pas, l'admin peut enregistrer tout ce que Catapult fait pour un
membre précis. Il s'active et se coupe depuis le menu d'actions du membre (page Membres de l'admin) ; un badge
signale les membres en débug. Il reste actif jusqu'à ce que l'admin le coupe, sans expiration.

Pendant le mode, pour ce membre seulement :

- **sa session web** : replay Sentry de la session entière et traces à 100 %, à partir du prochain chargement de
  page (les champs saisis restent masqués dans le replay) ;
- **ses échanges avec les services externes** : chaque requête et réponse HTTP de `catapult-api` faite pour lui
  (Twitch, Steam, IGDB, Xbox, Minecraft/MSA, DoesTheDogDie…), y compris en arrière-plan (détection de jeu, mise à
  jour de catégorie, sync des amis…), ainsi que les messages entrants qui le concernent (notifications EventSub,
  retours OAuth de ses liaisons de compte). Les corps sont tronqués à 65 536 caractères.

L'admin relit ces échanges dans l'admin (filtrables par service, sens, période et erreur / succès, avec le détail
de chacun) et, si Sentry est configuré, dans Sentry à côté du replay.

- **Secrets** : à l'activation, l'admin choisit s'ils sont masqués (par défaut) ou en clair. Le choix couvre les
  secrets du membre et ceux de l'application (tokens, clés d'API, client secrets), en base comme dans Sentry. Pour
  le changer, couper puis réactiver.
- **Autres membres** : un appel groupé n'est enregistré que pour la part du membre (requête comprise, l'échange est
  marqué « filtré ») ; dans tout échange, les identifiants d'un autre membre Catapult sont remplacés par `[membre]`.
  Les tiers qui ne sont pas membres restent tels quels.
- **Conservation** : les échanges sont supprimés 30 jours après la coupure, ou tout de suite par la suppression
  manuelle, qui efface tout l'enregistrement du membre. Une réactivation reprend avec ce qui reste.
- **Le membre ne voit rien** : aucun bandeau ni indication dans son interface. Le mode se discute avec lui en dehors
  de Catapult.
- **Sentry de l'api** : `catapult-api` envoie à Sentry via `API_SENTRY_DSN` (vide : Sentry coupé côté api,
  l'enregistrement en base marche quand même), et rien d'autre que les données des membres en débug.
  `SENTRY_ENVIRONMENT` nomme l'environnement (`production` par défaut).

Non couverts : le login Twitch (l'échange du code OAuth a lieu avant que le membre soit connu) et les appels faits
sans membre identifiable (préchauffage du cache des catégories, CCL de l'admin, registres well-known).

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
| `API_SENTRY_DSN` | Non | DSN Sentry de `catapult-api`, qui n'envoie que les membres en mode débug (vide : coupé) |
| `SENTRY_ENVIRONMENT` | Non | Environnement Sentry de `catapult-api` (`production` par défaut) |

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
