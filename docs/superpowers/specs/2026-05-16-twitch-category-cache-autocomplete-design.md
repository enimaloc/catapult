# Design : Cache local des catégories Twitch + autocomplétion enrichie

**Date** : 2026-05-16  
**Statut** : Validé

---

## Contexte

L'autocomplétion "Catégorie Twitch" dans les formulaires de l'application effectue actuellement un appel live à l'API Twitch (`/search/categories`) à chaque frappe. L'objectif est de :

1. Pré-charger un cache local des catégories Twitch en base de données au démarrage.
2. Servir l'autocomplétion depuis ce cache (avec fallback live + mise en cache des nouvelles entrées).
3. Enrichir le rendu de l'autocomplétion avec la miniature du jeu (`box_art_url`).

---

## Portée

Les trois formulaires qui possèdent un champ "Catégorie Twitch" sont concernés :
- `fragments/bindings.html` — édition inline d'un binding
- `admin/global-process-rules.html` — règles de process globales (admin)
- `fragments/obs-setup.html` — configuration OBS

---

## Architecture

### 1. Base de données

**Nouvelle table** : `twitch_category_cache`

| Colonne | Type | Contraintes | Description |
|---------|------|-------------|-------------|
| `id` | `VARCHAR(32)` | PK | ID Twitch de la catégorie |
| `name` | `VARCHAR(255)` | NOT NULL | Nom affiché |
| `box_art_url` | `VARCHAR(512)` | nullable | URL template de l'image boîte |
| `igdb_id` | `VARCHAR(32)` | nullable | ID IGDB associé (jointure avec `igdb_game_cache`) |
| `cached_at` | `TIMESTAMP` | NOT NULL | Horodatage d'insertion/mise à jour |

**Index** :
- `idx_twitch_category_name` sur `name` (ILIKE search)
- `idx_twitch_category_cached_at` sur `cached_at` (invalidation TTL)

**Migration Flyway** : `V17__twitch_category_cache.sql`

### 2. Couche domaine

**Entité JPA** : `TwitchCategoryCache`  
Champs : `id`, `name`, `boxArtUrl`, `igdbId`, `cachedAt`.

**Repository** : `TwitchCategoryCacheRepository`  
Méthodes nécessaires :
- `findByNameContainingIgnoreCase(String name)` — pour l'autocomplétion DB
- `findById(String id)` — lookup unitaire
- `saveAll(Collection<TwitchCategoryCache>)` — insertion batch

### 3. Service : `TwitchCategoryService`

Service dédié (pattern cohérent avec `SteamLibraryCacheService`, `IgdbService`).

#### Pré-chargement au démarrage (`prewarmCategoryCache`)

- Exécuté en `@Async @PostConstruct` (`@DependsOn("flyway")`).
- Balayage séquentiel des IDs Twitch par batches de 100 :
  ```
  GET /helix/games?id=0&id=1&...&id=99
  GET /helix/games?id=100&id=101&...&id=199
  ...
  ```
- Chaque batch retournant des résultats : stockage en base (`saveAll`).
- Arrêt au premier batch retournant 0 résultats.
- TTL : les entrées existantes dont `cached_at` est dans le TTL ne sont pas ré-fetchées (skip).

#### Recherche pour autocomplétion (`searchCategories`)

1. Chercher en DB : `findByNameContainingIgnoreCase(query)` limité à 8 résultats dans le TTL.
2. Si résultats suffisants (≥ 1 entrée valide dans TTL) → retourne immédiatement.
3. Sinon → appelle `/search/categories?query=...&first=8` Twitch live.
4. Stocke les nouvelles entrées en base (upsert sur `id`).
5. Retourne les résultats.

#### Configuration

```properties
app.twitch.category-cache-ttl-hours=24
```

### 4. Modification de `TwitchService`

La méthode `searchCategories(UserAccount user, String query)` est remplacée par un appel à `TwitchCategoryService.searchCategories(query, user)`. La logique d'appel Twitch live est déplacée dans `TwitchCategoryService`.

### 5. API endpoint

`GET /api/games/search?q=<query>`  
Réponse JSON (inchangé structurellement, champ `boxArtUrl` ajouté) :
```json
[
  { "id": "512980", "name": "Call of Duty: Modern Warfare", "boxArtUrl": "https://..." },
  ...
]
```

Le record `TwitchCategory` est enrichi : `record TwitchCategory(String id, String name, String boxArtUrl)`.

### 6. UI — Autocomplétion enrichie

**CSS** (ajout dans les pages concernées ou `app.css`) :
```css
.game-results li { display: flex; align-items: center; gap: 8px; padding: 6px 12px; }
.game-results li img { width: 30px; height: 40px; object-fit: cover; border-radius: 2px; }
```

**JS** — la fonction `fetchSuggestions` (actuellement dupliquée dans 3 templates) est refactorisée en fonction commune dans un fichier JS partagé ou dans `app.js`. Elle génère un `<li>` avec `<img>` + `<span>` au lieu du texte simple.

La `box_art_url` retournée par Twitch contient `{width}` et `{height}` comme templates à remplacer (ex: `30x40`).

---

## Flux de données

```
Démarrage app
  └─> TwitchCategoryService.prewarmCategoryCache() [@Async]
        └─> /helix/games?id=0..99 → batch 1
        └─> /helix/games?id=100..199 → batch 2
        └─> ... jusqu'à batch vide
        └─> INSERT INTO twitch_category_cache (id, name, box_art_url, cached_at)

Frappe utilisateur dans champ autocomplétion
  └─> GET /api/games/search?q=zelda
        └─> TwitchCategoryService.searchCategories("zelda", user)
              ├─> DB: SELECT * FROM twitch_category_cache WHERE name ILIKE '%zelda%' LIMIT 8
              ├─> Si résultats dans TTL → retourne
              └─> Sinon → GET /search/categories?query=zelda → upsert en base → retourne
```

---

## Gestion des erreurs

- Si Twitch API indisponible lors du prewarm : log WARN, pas de crash, cache partielle acceptable.
- Si Twitch API indisponible lors d'une recherche live : retourne les résultats DB même périmés (dégradation gracieuse).
- Si la DB est vide et Twitch indisponible : retourne `[]`.

---

## Fichiers à créer / modifier

| Fichier | Action |
|---------|--------|
| `src/main/resources/db/migration/V17__twitch_category_cache.sql` | Créer |
| `src/main/java/.../domain/TwitchCategoryCache.java` | Créer |
| `src/main/java/.../repository/TwitchCategoryCacheRepository.java` | Créer |
| `src/main/java/.../service/TwitchCategoryService.java` | Créer |
| `src/main/java/.../service/TwitchService.java` | Modifier (`searchCategories` délègue) |
| `src/main/java/.../web/AppController.java` | Modifier (record `TwitchCategory` + `boxArtUrl`) |
| `src/main/resources/templates/app.html` | Modifier (JS autocomplete enrichi) |
| `src/main/resources/templates/fragments/bindings.html` | Modifier (CSS suggestion) |
| `src/main/resources/templates/admin/global-process-rules.html` | Modifier (JS + CSS suggestion) |
| `src/main/resources/templates/fragments/obs-setup.html` | Modifier (JS + CSS suggestion) |
| `src/main/resources/application.properties` | Modifier (TTL property) |

---

## Non-traité (hors périmètre)

- Invalidation manuelle du cache (pas d'endpoint admin de reset).
- Pagination de l'autocomplétion (max 8 résultats, comme l'actuel).
- Mise à jour incrémentale du cache (pas de scheduled job, uniquement prewarm démarrage + lazy).
