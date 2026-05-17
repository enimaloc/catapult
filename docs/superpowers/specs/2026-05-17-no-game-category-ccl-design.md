# Design — Catégorie & CCLs par défaut (mode sans jeu)

**Date :** 2026-05-17  
**Scope :** `/app` — nouvelle section de configuration pour l'état "aucun jeu détecté"

---

## Contexte

Quand aucun jeu n'est détecté, Catapult peut déjà réinitialiser la catégorie Twitch via
`TwitchService.resetToDefault()`. Le champ `noGameTwitchGameId` / `noGameTwitchGameName`
existe dans `UserSettings` mais n'est exposé dans aucune UI. Les CCLs pour cet état
n'existent pas encore en base.

---

## Objectif

Ajouter dans `/app` une section dédiée permettant à l'utilisateur de :
1. Choisir une catégorie Twitch à appliquer quand aucun jeu n'est détecté
2. Choisir des CCLs (Content Classification Labels) à appliquer dans ce même état

---

## Architecture

### Nouveaux fichiers

| Fichier | Rôle |
|---|---|
| `src/main/resources/db/migration/V16__no_game_ccls.sql` | Migration Flyway — crée la table `user_settings_no_game_ccls` |
| `src/main/resources/templates/fragments/no-game-settings.html` | Fragment Thymeleaf — formulaire de config sans-jeu |

### Fichiers modifiés

| Fichier | Changement |
|---|---|
| `domain/UserSettings.java` | Ajouter `@ElementCollection Set<String> noGameCcls` |
| `service/TwitchService.java` | `resetToDefault()` applique les CCLs en plus de la catégorie |
| `web/AppController.java` | GET `/fragments/no-game-settings` + POST `/settings/no-game` |
| `templates/app.html` | Inclure le fragment `no-game-settings` |

---

## Design détaillé

### Base de données

Nouvelle table de jointure (cohérent avec `game_binding_ccls`) :

```sql
CREATE TABLE user_settings_no_game_ccls (
    user_id UUID NOT NULL REFERENCES user_settings(user_id) ON DELETE CASCADE,
    ccl_id  VARCHAR(64) NOT NULL,
    PRIMARY KEY (user_id, ccl_id)
);
```

### `UserSettings.noGameCcls`

```java
@ElementCollection(fetch = FetchType.EAGER)
@CollectionTable(name = "user_settings_no_game_ccls",
                 joinColumns = @JoinColumn(name = "user_id"))
@Column(name = "ccl_id")
private Set<String> noGameCcls = new HashSet<>();
```

### Endpoint GET `/fragments/no-game-settings`

Renvoie le fragment avec :
- `noGameSettings` : `UserSettings` de l'utilisateur
- `availableCcls` : liste de `TwitchCclDefinition` (déjà utilisée dans bindings)

### Endpoint POST `/settings/no-game`

Paramètres : `twitchGameId`, `twitchGameName`, `ccls[]` (multi-value)

Actions :
1. Met à jour `UserSettings` (catégorie + CCLs)
2. Si aucun jeu en cours (`gameStateService.getLastKnownGame(user).isEmpty()`), appelle
   `TwitchService.resetToDefault(user)` pour appliquer immédiatement

### `TwitchService.resetToDefault()`

Modification pour inclure les CCLs dans le PATCH :

```java
Map<String, Object> body = new LinkedHashMap<>();
body.put("game_id", settings.getNoGameTwitchGameId());
if (globalCclEnabled && !settings.getNoGameCcls().isEmpty()) {
    body.put("content_classification_labels", buildCclPayload(settings.getNoGameCcls()));
}
```

### Fragment UI (`no-game-settings.html`)

- Input de recherche de catégorie avec autocomplete (pattern identique à `obs-setup.html`)
- Checkboxes CCL (pattern identique à la section d'édition des bindings)
- Bouton "Enregistrer"

---

## Flux complet

```
User saisit une catégorie + CCLs → POST /settings/no-game
  → UserSettings mis à jour
  → Si aucun jeu en cours → TwitchService.resetToDefault()
      → PATCH /helix/channels { game_id, content_classification_labels }
```

---

## Ce qui ne change pas

- `TwitchService.updateChannel()` (utilisé pour les bindings avec jeu)
- Le fragment `status.html` — inchangé
- Le fragment `bindings.html` — inchangé
- La logique de détection de jeu — inchangée

---

## Tests

- Vérifier que `POST /settings/no-game` met bien à jour `UserSettings` (catégorie + CCLs)
- Vérifier que `resetToDefault()` inclut les CCLs dans le payload Twitch
- Vérifier que si un jeu est en cours, `resetToDefault()` n'est pas appelé depuis le POST
