# Design — Fallback jeu pour bindings incomplets

**Date :** 2026-05-26  
**Scope :** Catapult — gestion des bindings `INCOMPLETE`

---

## Contexte

Quand un jeu est détecté mais que son binding ne peut pas être résolu (pas trouvé sur IGDB, ou pas de `twitchGameId` disponible), le binding est créé avec le statut `INCOMPLETE`. Actuellement, `GameEventListener.onGameDetected()` **skippe** la mise à jour Twitch pour ces bindings, laissant la chaîne en affichant la catégorie précédente indéfiniment.

L'objectif est d'appliquer un **jeu fallback configurable par l'utilisateur** tant que le binding reste incomplet.

---

## Décisions de design

| Question | Décision |
|---|---|
| Setting distinct du no-game ? | Oui — champ séparé dans `UserSettings` |
| Inclure des CCLs ? | Oui — même structure que `noGameCcls` |
| Interface de configuration ? | Page settings existante, nouveau fragment à côté du no-game |
| Approche d'implémentation | Inline dans `GameEventListener.onGameDetected()` (Approche A) |

---

## Architecture

### 1. `UserSettings` — nouveaux champs

```java
// Dans UserSettings.java
private String incompleteFallbackTwitchGameId;
private String incompleteFallbackTwitchGameName;

@ElementCollection(fetch = FetchType.EAGER)
@CollectionTable(
    name = "user_settings_incomplete_fallback_ccls",
    joinColumns = @JoinColumn(name = "user_id")
)
@Column(name = "ccl_id")
private Set<String> incompleteFallbackCcls = new HashSet<>();
```

### 2. Migration DB — `V10__add_incomplete_fallback_to_user_settings.sql`

```sql
ALTER TABLE user_settings
    ADD COLUMN incomplete_fallback_twitch_game_id   VARCHAR,
    ADD COLUMN incomplete_fallback_twitch_game_name VARCHAR;

CREATE TABLE user_settings_incomplete_fallback_ccls (
    user_id UUID NOT NULL REFERENCES user_settings(user_id) ON DELETE CASCADE,
    ccl_id  VARCHAR NOT NULL
);
```

### 3. `GameEventListener` — logique modifiée

**Avant :**
```java
if (binding.getStatus() == GameBinding.Status.INCOMPLETE || binding.isIgnored()) {
    log.debug("Binding is {} — skipping update for user {}", binding.getStatus(), user.getId());
    return;
}
```

**Après :**
```java
if (binding.isIgnored()) {
    log.debug("Binding is ignored — skipping update for user {}", user.getId());
    return;
}

if (binding.getStatus() == GameBinding.Status.INCOMPLETE) {
    log.debug("Binding is INCOMPLETE — checking fallback for user {}", user.getId());
    applyIncompleteFallback(user);
    return;
}
```

**Nouvelle méthode `applyIncompleteFallback()`** :
```java
private void applyIncompleteFallback(UserAccount user) {
    userSettingsRepository.findById(user.getId()).ifPresent(settings -> {
        if (settings.getIncompleteFallbackTwitchGameId() == null
                || settings.getIncompleteFallbackTwitchGameId().isBlank()) {
            return; // Pas de fallback configuré, on skippe comme avant
        }
        GameBinding fallback = new GameBinding();
        fallback.setUser(user);
        fallback.setSourceType(GameBinding.SourceType.MANUAL);
        fallback.setSourceName("incomplete-fallback");
        fallback.setTwitchGameId(settings.getIncompleteFallbackTwitchGameId());
        fallback.setTwitchGameName(settings.getIncompleteFallbackTwitchGameName());
        fallback.getCcls().addAll(settings.getIncompleteFallbackCcls());
        fallback.setStatus(GameBinding.Status.MANUAL);

        if (streamStateService.isLive(user)) {
            twitchService.updateChannel(user, fallback);
        } else {
            streamStateService.storePending(user, fallback);
        }
    });
}
```

### 4. UI — Fragment `fragments/incomplete-fallback-settings.html`

Identique en structure à `no-game-settings.html` :
- Champ de recherche Twitch category (`gameSearch-incompleteGame`)
- Checkboxes CCLs
- Form POST vers `/channels/{username}/settings/incomplete-fallback`

### 5. `ChannelController` — nouveaux endpoints

```java
// GET fragment
@GetMapping("/channels/{username}/fragments/incomplete-fallback-settings")
public String fragmentIncompleteFallbackSettings(...) { ... }

// POST save
@PostMapping("/channels/{username}/settings/incomplete-fallback")
public String saveIncompleteFallbackSettings(
        @PathVariable String username,
        @AuthenticationPrincipal CatapultOAuth2User principal,
        @RequestParam(required = false) String twitchGameId,
        @RequestParam(required = false) String twitchGameName,
        @RequestParam(required = false) Set<String> ccls) { ... }
```

---

## Flux complet

```
Poll → GameDetectedEvent
         ↓
    resolveOrCreate()
         ↓ INCOMPLETE
    applyIncompleteFallback()
         ↓
    settings.incompleteFallbackTwitchGameId ?
         ├── null/blank → skip (comportement actuel)
         └── configuré  → createTransientFallbackBinding()
                               ↓
                         isLive ? updateChannel() : storePending()
```

Quand le binding est manuellement résolu via `updateBinding()`, `twitchService.updateChannel()` est appelé immédiatement — le fallback est remplacé automatiquement.

---

## Fichiers à créer / modifier

| Fichier | Action |
|---|---|
| `domain/UserSettings.java` | Ajout de 3 champs |
| `db/migration/V10__add_incomplete_fallback_to_user_settings.sql` | Nouveau |
| `event/GameEventListener.java` | Modifier `onGameDetected()`, ajouter `applyIncompleteFallback()` |
| `templates/fragments/incomplete-fallback-settings.html` | Nouveau |
| `web/ChannelController.java` | 2 nouveaux endpoints |
| `templates/app.html` (ou template principal) | Inclure le nouveau fragment |

---

## Non-inclus (hors scope)

- Retry automatique des bindings INCOMPLETE lors du même cycle de polling (conception existante : le retry se fait quand le jeu change ou sur résolution manuelle)
- Logs d'activité pour l'application du fallback (peut être ajouté plus tard)
