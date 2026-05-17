# Mock Steam — Game Picker (autocomplete IGDB)

**Date:** 2026-05-17  
**Scope:** Admin members page, profil `mock-steam`

## Problème

Le formulaire "Mock Steam" dans `/admin/members` demande deux champs texte libres (`Game ID` et `Nom du jeu`). L'utilisateur doit connaître les IDs à la main. Objectif : remplacer ça par une autocomplétion sur IGDB, identique au picker de catégorie Twitch.

## Design

### 1. Endpoint de recherche IGDB

**`GET /api/mock/igdb/search?q={query}`**  
- Profil `mock-steam` (via `@Profile("mock-steam")`)  
- Ajouté dans `AdminMembersMockController` (pas de nouveau fichier)  
- Délègue à `IgdbService.searchByName(q)` → retourne les 5 premiers résultats  
- Réponse : `[{"id": "igdbId", "name": "gameName"}, …]`  
- `q` vide ou < 2 caractères → liste vide  
- Si IGDB non configuré (`clientId` vide) → liste vide

### 2. `app.js` — support `data-search-url`

Dans `gameSearch()`, la ligne :
```js
fetch('/api/games/search?q=' + encodeURIComponent(q))
```
devient :
```js
fetch((input.dataset.searchUrl || '/api/games/search') + '?q=' + encodeURIComponent(q))
```
Rétrocompatible : tous les usages existants sans `data-search-url` continuent à appeler Twitch.

### 3. `members.html` — colonne Mock Steam

Remplace les deux `<input type="text">` par le pattern autocomplete existant :

```html
<!-- input visible pour la recherche -->
<input type="text"
       id="steam-search-{member.id}"
       oninput="gameSearch(event)"
       data-results-id="steam-results-{member.id}"
       data-gameid-field="steam-gameId-{member.id}"
       data-gamename-field="steam-gameName-{member.id}"
       data-search-url="/api/mock/igdb/search"
       placeholder="Rechercher un jeu…"
       autocomplete="off">
<ul id="steam-results-{member.id}" class="game-results"></ul>

<!-- champs cachés soumis au formulaire -->
<input type="hidden" name="gameId"   id="steam-gameId-{member.id}">
<input type="hidden" name="gameName" id="steam-gameName-{member.id}">
```

Le bouton "Set" et le formulaire `POST /admin/members/{id}/steam/set` restent identiques.

## Flux utilisateur

1. L'admin tape ≥ 2 caractères dans le champ → appel IGDB déboncé 300 ms  
2. Dropdown s'affiche avec les suggestions (nom uniquement, pas de cover — on n'a pas d'image IGDB facile)  
3. Clic sur une suggestion → remplit les champs cachés `gameId` (IGDB ID) et `gameName`  
4. Clic "Set" → POST existant → `MockSteamApiClient.setGameForUser(steamId, igdbId, gameName)`

## Ce qui ne change pas

- `POST /admin/members/{id}/steam/set` — inchangé  
- `MockSteamApiClient` — inchangé  
- Comportement "Clear" — inchangé  
- `/api/games/search` (Twitch) — inchangé

## Hors scope

- Cover/artwork dans les suggestions (pas d'URL image directe depuis IGDB search)  
- Validation côté serveur que `gameId` est un vrai ID IGDB  
- Global game mock (non demandé)
