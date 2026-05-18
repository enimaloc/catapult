# Template Layout Migration — Design

**Date:** 2026-05-18  
**Status:** Approved

## Contexte

`login.html` a initié une approche de layout orientée template via `template::layout`, un fragment Thymeleaf centralisé dans `template.html`. Les autres pages utilisent des approches hétérogènes (fragments séparés, head inline, nav inline). Cette migration uniformise toutes les pages sur le pattern `template::layout`.

## Problèmes identifiés

1. **Incohérence** — seul `login.html` utilise `template::layout`. Les autres utilisent `fragments/head`, `fragments/nav`, ou du head/nav inline.
2. **`th:include` déprécié** — `template.html:68` utilise `th:include` (déprécié en Thymeleaf 3). Conservé fonctionnellement pour l'instant, noté pour futur nettoyage.
3. **`<main class="container">` codé en dur** — `igdb-explorer.html` a un layout full-height `calc(100vh - 56px)` incompatible avec `.container`.
4. **Bannière dupliquée** — `app.html` a sa propre copie de la bannière "suppression en cours" déjà gérée par `template.html`.
5. **Nav dupliquée** — `template.html` et `fragments/nav.html` ont le même contenu nav. Les pages appelant `fragments/nav` deviennent redondantes.
6. **Fragments orphelins** — `fragments/head.html` et `fragments/nav.html` ne seront plus référencés après migration. À supprimer.
7. **CSRF meta tags** — `igdb-explorer.html` a des `<meta name="_csrf">` dans son head qui doivent passer par `additional_head`.

## Approche choisie

**Template-centrique** : `template.html` gère tout le chrome partagé (head, banner, nav, main wrapper). Chaque page passe uniquement son contenu via `~{::body}`.

## Design

### Signature étendue de `template.html`

```
th:fragment="layout(title, active_page, content, additional_head, main_class)"
```

Paramètres :
- `title` — fragment `~{::title}` de la page
- `active_page` — string identifiant l'onglet actif dans la nav (`'app'`, `'ccl'`, etc.), `null` pour login
- `content` — fragment `~{::body}` de la page
- `additional_head` — fragment optionnel pour CSS/meta page-spécifique, `null` si vide
- `main_class` — classe CSS du wrapper `<main>` :
  - `'container'` → `<main class="container" th:include="${content}">`
  - `null` ou `''` → `th:include` direct dans body, sans wrapper `<main>`

### Wrapper conditionnel dans `template.html`

```html
<th:block th:if="${main_class != null && !main_class.isEmpty()}">
  <main th:class="${main_class}" th:include="${content}"></main>
</th:block>
<th:block th:unless="${main_class != null && !main_class.isEmpty()}" th:include="${content}"></th:block>
```

### Migration par page

| Page | `active_page` | `main_class` | Changements |
|------|--------------|-------------|-------------|
| `login.html` | `null` | `null` | Ajouter 5e param |
| `app.html` | `'app'` | `'container'` | Full migration ; retirer banner et nav dupliqués ; garder modal et scripts (ils passent dans `~{::body}`) |
| `admin/ccl.html` | `'ccl'` | `'container'` | Remplacer `<head th:replace>` + `<div th:replace nav>` ; passer les styles inline via `additional_head` |
| `admin/members.html` | `'members'` | `'container'` | Remplacer `<head th:replace>` + `<div th:replace nav>` |
| `admin/global-process-rules.html` | `'process-rules'` | `'container'` | Migrer head inline et nav ; passer htmx via template (inclus par défaut) |
| `dev/igdb-explorer.html` | `'igdb'` | `''` | CSS inline + CSRF meta → `additional_head` ; supprimer nav call ; layout full-width sans wrapper `<main>` |

### Suppressions

- `src/main/resources/templates/fragments/head.html` — supprimé
- `src/main/resources/templates/fragments/nav.html` — supprimé

### Notes importantes

- Les scripts page-spécifiques en fin de `<body>` (ex: `app.html`) seront inclus à l'intérieur du `<main>` via `th:include`. Fonctionnellement correct, sémantiquement acceptable.
- `th:include` est déprécié mais conservé : son remplacement propre (`th:insert` avec `~{::body}`) insèrerait le tag `<body>` complet comme enfant, ce qui est invalide. Ce point est reporté à un futur refactor.
- `fragments/nav.html` contient déjà la même nav que `template.html` (seule différence : la condition `th:if="${user != null}"` sur les nav-links dans template). La suppression est sans risque.
