# Global Process Rules — Design Spec

**Date:** 2026-05-14  
**Status:** Approved

## Contexte

Les bindings processus → jeu actuels (`ProcessBinding`) sont strictement par utilisateur. L'admin souhaite définir des règles globales qui s'appliquent en fallback à tous les utilisateurs, sans qu'ils aient à les configurer manuellement.

## Résumé

Étendre `ProcessBinding` pour supporter des règles globales (sans propriétaire) avec matching par regex sur le nom du processus, et les mêmes prédicats que les bindings utilisateur.

---

## 1. Schéma & modèle de données

### Migration V16

```sql
-- Rendre user_id nullable (règle globale = NULL)
ALTER TABLE process_binding ALTER COLUMN user_id DROP NOT NULL;

-- Remplacer la contrainte unique par une contrainte partielle
ALTER TABLE process_binding DROP CONSTRAINT process_binding_user_id_process_name_key;
CREATE UNIQUE INDEX uq_process_binding_user
    ON process_binding (user_id, process_name)
    WHERE user_id IS NOT NULL;

-- Flag pour distinguer exact vs regex
ALTER TABLE process_binding ADD COLUMN is_regex BOOLEAN NOT NULL DEFAULT FALSE;
```

### Entité `ProcessBinding`

- `user` : `@ManyToOne(optional = true)` — `null` pour une règle globale
- `isRegex` : `boolean` (défaut `false`) — si `true`, `processName` est interprété comme un pattern Java regex
- Méthode utilitaire : `boolean isGlobal()` → `user == null`

Les `ProcessPredicate` ne changent pas (FK vers `binding_id`, indépendant du scope).

---

## 2. Résolution dans `ObsGameGetter`

### Chaîne de résolution (priorité décroissante)

1. **Bindings utilisateur** — matching exact sur `processName` + prédicats (inchangé)
2. **Règles globales admin** — matching regex sur `processName` + prédicats (nouveau)
3. **IGDB par nom d'exe** — fallback existant (inchangé)

### Repository

```java
// Nouveau dans ProcessBindingRepository
List<ProcessBinding> findByUserIsNull();
```

Les règles globales sont peu nombreuses → chargement complet en mémoire, matching regex côté JVM.

### Logique de matching

```java
private boolean matchesPattern(ProcessBinding rule, String name) {
    if (!rule.isRegex()) return rule.getProcessName().equals(name);
    return name.matches(rule.getProcessName());
}
```

> **Note :** `String.matches()` ancre implicitement le pattern (`^...$`). L'admin doit écrire `.*minecraft.*\.exe` pour un match partiel — à documenter dans l'UI.

---

## 3. Interface admin

### Localisation

- Controller : `AdminProcessRuleController` sur `/admin/process-rules`
- Template : `src/main/resources/templates/admin/global-process-rules.html`
- Fragment inclus dans la page admin existante (aux côtés du fragment CCL)

### Tableau des règles

Colonnes : **Pattern**, **Regex** (badge), **Jeu Twitch**, **Prédicats** (via `<details>`), **Actions** (supprimer).

### Formulaire d'ajout

- Champ **Pattern processus** (texte libre)
- Checkbox **"Pattern regex"**
- Recherche jeu Twitch (autocomplete `/api/games/search`, identique à l'UI user)
- Prédicats ajoutables via `<details>` — même structure que `obs-setup.html`

### Validation serveur

Avant persistance, le controller tente `Pattern.compile(pattern)` — si exception, retour HTTP 400 avec message d'erreur. Les bindings sans regex (`isRegex = false`) ne sont pas soumis à cette validation.

---

## 4. Gestion des erreurs

| Cas | Comportement |
|---|---|
| Pattern regex invalide (POST) | HTTP 400, message d'erreur affiché dans le formulaire |
| Règle globale sans prédicats | Toujours matche si le pattern est satisfait |
| Règle globale avec prédicats, OS différent | Prédicats ignorés (comportement identique aux bindings user) |
| Aucune règle globale | `findByUserIsNull()` retourne liste vide, étape 2 skippée |

---

## 5. Tests

- `ProcessPredicateEvaluatorTest` — inchangé, pas de dépendance au scope
- `ObsGameGetterTest` — ajouter cas : règle globale matche quand pas de binding user ; règle globale ne matche pas si binding user existe
- `AdminProcessRuleControllerTest` — CRUD règles globales, validation regex invalide

---

## Fichiers à créer / modifier

| Fichier | Action |
|---|---|
| `db/migration/V16__global_process_rules.sql` | Créer |
| `domain/ProcessBinding.java` | Modifier (`user` optional, ajouter `isRegex`) |
| `repository/ProcessBindingRepository.java` | Modifier (ajouter `findByUserIsNull`) |
| `getter/ObsGameGetter.java` | Modifier (insérer étape 2) |
| `web/AdminProcessRuleController.java` | Créer |
| `templates/admin/global-process-rules.html` | Créer |
