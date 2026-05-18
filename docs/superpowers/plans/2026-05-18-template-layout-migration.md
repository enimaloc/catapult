# Template Layout Migration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrer toutes les pages HTML vers le pattern Thymeleaf `template::layout` centralisé, en éliminant la duplication de head/nav/banner.

**Architecture:** `template.html` est le layout racine — il gère head, banner, nav et le wrapper `<main>`. Chaque page passe son titre, son `active_page`, son contenu (`~{::body}`) et optionnellement des éléments head page-spécifiques (`~{::style}`). Un 5e paramètre `main_class` permet de contrôler le wrapper `<main>` (ou de le supprimer pour les layouts full-width).

**Tech Stack:** Thymeleaf 3.x, Spring Boot, Spring Security Thymeleaf extras.

---

## Fichiers modifiés / supprimés

| Fichier | Action |
|---------|--------|
| `src/main/resources/templates/template.html` | Modifier — 5e param + wrapper conditionnel |
| `src/main/resources/templates/login.html` | Modifier — ajouter 5e param `null` |
| `src/main/resources/templates/app.html` | Modifier — full migration |
| `src/main/resources/templates/admin/ccl.html` | Modifier — full migration |
| `src/main/resources/templates/admin/members.html` | Modifier — full migration |
| `src/main/resources/templates/admin/global-process-rules.html` | Modifier — full migration |
| `src/main/resources/templates/dev/igdb-explorer.html` | Modifier — full migration, main_class='' |
| `src/main/resources/templates/fragments/head.html` | Supprimer |
| `src/main/resources/templates/fragments/nav.html` | Supprimer |

---

### Task 1 — Mettre à jour `template.html` + `login.html` (atomique)

Ces deux fichiers doivent être changés ensemble : `template.html` passe à 5 paramètres, `login.html` est le seul appelant actuel et doit être mis à jour immédiatement.

**Files:**
- Modify: `src/main/resources/templates/template.html`
- Modify: `src/main/resources/templates/login.html`

- [ ] **Étape 1 : Modifier `template.html`**

Remplacer la ligne 3 :
```html
<!-- AVANT -->
th:fragment="layout(title, active_page, content, additional_head)"

<!-- APRÈS -->
th:fragment="layout(title, active_page, content, additional_head, main_class)"
```

Remplacer la ligne 68 (le `<main>` actuel) :
```html
<!-- AVANT -->
<main class="container" th:include="${content}"></main>

<!-- APRÈS -->
<th:block th:if="${main_class != null && !main_class.isEmpty()}">
    <main th:class="${main_class}" th:include="${content}"></main>
</th:block>
<th:block th:unless="${main_class != null && !main_class.isEmpty()}" th:include="${content}"></th:block>
```

Résultat complet de `template.html` après modification :
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" xmlns:sec="http://www.thymeleaf.org/extras/springsecurity6"
      th:fragment="layout(title, active_page, content, additional_head, main_class)">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title th:replace="${title}">Catapult</title>
    <script>(function () {
        const t = localStorage.getItem('theme');
        if (t) document.documentElement.dataset.theme = t;
    })();</script>
    <link rel="stylesheet" th:href="@{/css/app.css}">
    <script th:src="@{/webjars/htmx.org/dist/htmx.min.js}"></script>
    <script th:src="@{/js/app.js}"></script>
    <th:block th:replace="${additional_head != null} ? additional_head : ~{}"></th:block>
</head>
<body>

<div th:if="${user != null && isPendingDeletion}" class="banner banner-danger">
    <strong th:text="#{app.banner.pending}">⚠ Suppression de compte en cours.</strong>
    <span th:text="#{app.banner.deletion_date}">Ton compte sera supprimé définitivement le</span>
    <strong th:text="${#temporals.format(user.deletionRequestedAt, 'dd/MM/yyyy')}"></strong>.
    <form th:action="@{/settings/cancel-deletion}" method="post" style="display:inline">
        <button type="submit" class="btn btn-sm btn-outline" th:text="#{common.cancel}">Annuler</button>
    </form>
</div>

<div th:if="${user != null}" sec:authorize="hasAuthority('ROLE_PREVIOUS_ADMINISTRATOR')"
     style="background:var(--color-warning,#f59e0b);color:#000;padding:0.5rem 1rem;display:flex;align-items:center;gap:1rem;font-size:0.9rem;">
    <span><span th:text="#{nav.impersonate_as}">Connecté en tant que</span> <strong
            sec:authentication="principal.username"></strong></span>
    <form th:action="@{/admin/impersonate/exit}" method="post" style="display:inline"> <!-- nosemgrep -->
        <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
        <button type="submit" class="btn btn-sm" th:text="#{nav.back_to_admin}">Retour à l'admin</button>
    </form>
</div>
<nav class="navbar">
    <a th:href="@{/app}" class="nav-brand">🚀 Catapult</a>
    <div class="nav-links" th:if="${user != null}">
        <a th:href="@{/app}" sec:authorize="hasRole('ADMIN')"
           th:classappend="${active_page == 'app'} ? ' active'">App</a>
        <a th:href="@{/admin/ccl}" sec:authorize="hasRole('ADMIN')"
           th:classappend="${active_page == 'ccl'} ? ' active'">Admin CCL</a>
        <a th:href="@{/admin/process-rules}" sec:authorize="hasRole('ADMIN')"
           th:classappend="${active_page == 'process-rules'} ? ' active'" th:text="#{nav.process_rules}">Règles
            processus</a>
        <a th:href="@{/admin/members}" sec:authorize="hasRole('ADMIN')"
           th:classappend="${active_page == 'members'} ? ' active'" th:text="#{nav.members}">Membres</a>
        <a th:href="@{/dev/igdb}" sec:authorize="hasRole('ADMIN')" th:classappend="${active_page == 'igdb'} ? ' active'"
           th:if="${#arrays.contains(@environment.getActiveProfiles(), 'dev')}">IGDB Explorer</a>
        <form th:action="@{/logout}" method="post" style="display:inline">
            <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
            <button type="submit" class="btn-link" th:text="#{nav.logout}">Déconnexion</button>
        </form>
    </div>
    <div class="theme-switcher">
        <button class="theme-btn" data-theme="dark" onclick="setTheme('dark')" title="Dark">🌙</button>
        <button class="theme-btn" data-theme="blanc" onclick="setTheme('blanc')" title="Blanc">☀️</button>
        <button class="theme-btn" data-theme="old-steam" onclick="setTheme('old-steam')" title="Old Steam">🎮</button>
        <button class="theme-btn" data-theme="nord" onclick="setTheme('nord')" title="Nord">❄️</button>
        <button class="theme-btn" data-theme="dracula" onclick="setTheme('dracula')" title="Dracula">🧛</button>
        <button class="theme-btn" data-theme="catppuccin" onclick="setTheme('catppuccin')" title="Catppuccin">🐱</button>
        <button class="theme-btn" data-theme="tokyo-night" onclick="setTheme('tokyo-night')" title="Tokyo Night">🌃
        </button>
    </div>
</nav>

<th:block th:if="${main_class != null && !main_class.isEmpty()}">
    <main th:class="${main_class}" th:include="${content}"></main>
</th:block>
<th:block th:unless="${main_class != null && !main_class.isEmpty()}" th:include="${content}"></th:block>

</body>
</html>
```

- [ ] **Étape 2 : Modifier `login.html`**

Ajouter le 5e paramètre `null` (la page login gère son propre centrage via `.login-page`, pas besoin de `<main class="container">`).

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" th:replace="~{template::layout(~{::title}, null, ~{::body}, null, null)}">
<head>
    <title>Catapult - Connexion</title>
</head>
<body>
<div class="login-page">
    <div class="login-container">
        <div class="login-card">
            <h1>🚀 Catapult</h1>
            <p class="subtitle" th:text="#{login.subtitle}">Mise à jour automatique de catégorie Twitch</p>
            <a th:href="@{/oauth2/authorization/twitch}" class="btn btn-twitch">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
                    <path d="M11.571 4.714h1.715v5.143H11.57zm4.715 0H18v5.143h-1.714zM6 0L1.714 4.286v15.428h5.143V24l4.286-4.286h3.428L22.286 12V0zm14.571 11.143l-3.428 3.428h-3.429l-3 3v-3H6.857V1.714h13.714z"/>
                </svg>
                <span th:text="#{login.button}">Se connecter avec Twitch</span>
            </a>
        </div>
    </div>
</div>
</body>
</html>
```

- [ ] **Étape 3 : Vérifier que l'application démarre sans erreur Thymeleaf**

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=mock 2>&1 | head -50
```
Attendu : pas d'exception `TemplateProcessingException`. La page `/login` doit retourner HTTP 200.

- [ ] **Étape 4 : Commit**

```bash
git add src/main/resources/templates/template.html src/main/resources/templates/login.html
git commit -m "refactor: extend template layout with main_class param and update login"
```

---

### Task 2 — Migrer `admin/ccl.html`

**Files:**
- Modify: `src/main/resources/templates/admin/ccl.html`

- [ ] **Étape 1 : Réécrire `admin/ccl.html`**

Remplacer tout le fichier. On retire :
- `<head th:replace>` → template.html le fournit
- `<div th:replace nav>` → template.html le fournit
- `<main class="container">` wrapper → `main_class='container'` le fournit

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
      th:replace="~{template::layout(~{::title}, 'ccl', ~{::body}, null, 'container')}">
<head>
    <title>Catapult — Admin CCL</title>
</head>
<body>
    <div style="display:flex; align-items:center; justify-content:space-between; margin-bottom:1rem;">
        <h2 th:text="#{admin.ccl.title}">Administration — CCL</h2>
        <form th:action="@{/admin/ccl/refresh}" method="post"> <!-- nosemgrep -->
            <button type="submit" class="btn btn-outline" th:text="#{ccl.refresh}"></button>
        </form>
    </div>

    <div th:if="${#lists.isEmpty(ccls)}" class="text-muted">
        <p th:text="#{ccl.no_result(#{ccl.refresh})}"></p>
    </div>

    <div th:unless="${#lists.isEmpty(ccls)}" class="table-container">
        <table class="table">
            <thead>
                <tr>
                    <th th:text="#{ccl.column.twitch}">CCL Twitch</th>
                    <th th:text="#{ccl.column.description}">Description</th>
                    <th th:text="#{ccl.column.igdb_mappings}">Descripteurs de contenu IGDB associés</th>
                    <th th:text="#{ccl.column.actions}">Actions</th>
                </tr>
            </thead>
            <tbody>
                <tr th:each="ccl : ${ccls}">
                    <td>
                        <strong th:text="${ccl.name}"></strong><br>
                        <span class="badge" th:text="${ccl.id}"></span>
                    </td>
                    <td class="text-muted" th:text="${ccl.description}"></td>
                    <td>
                        <form th:action="@{/admin/ccl/{id}/mappings(id=${ccl.id})}" method="post"> <!-- nosemgrep -->
                            <select name="igdbCategoryIds" multiple size="8" style="width:100%; min-width:250px;">
                                <option th:each="desc : ${igdbDescriptors}"
                                        th:value="${desc.id}"
                                        th:text="${desc.description}"
                                        th:selected="${ccl.mappedDescriptions.contains(desc.description)}">
                                </option>
                            </select>
                            <div style="margin-top:0.5rem;">
                                <button type="submit" class="btn btn-sm btn-primary" th:text="#{common.save}">Enregistrer</button>
                            </div>
                        </form>
                    </td>
                    <td>
                        <span th:each="desc : ${ccl.mappedDescriptions}" class="badge badge-ccl" th:text="${desc}"></span>
                    </td>
                </tr>
            </tbody>
        </table>
    </div>
</body>
</html>
```

- [ ] **Étape 2 : Vérifier que `/admin/ccl` retourne HTTP 200**

```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/admin/ccl
```
Attendu : `302` (redirect vers login si non authentifié) ou `200` si connecté. Pas d'exception Thymeleaf dans les logs.

- [ ] **Étape 3 : Commit**

```bash
git add src/main/resources/templates/admin/ccl.html
git commit -m "refactor: migrate admin/ccl to template layout"
```

---

### Task 3 — Migrer `admin/members.html`

**Files:**
- Modify: `src/main/resources/templates/admin/members.html`

- [ ] **Étape 1 : Réécrire `admin/members.html`**

On retire `<head th:replace>`, `<div th:replace nav>`. Pas d'`additional_head` nécessaire (pas de styles inline).

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
      th:replace="~{template::layout(~{::title}, 'members', ~{::body}, null, 'container')}">
<head>
    <title>Catapult — Membres</title>
</head>
<body>
    <h2 th:text="#{admin.members.title}">Administration — Membres</h2>

    <div th:if="${#lists.isEmpty(members)}" class="text-muted">
        <p th:text="#{admin.members.empty}">Aucun membre en base.</p>
    </div>

    <div th:unless="${#lists.isEmpty(members)}" class="card">
        <div class="table-container">
            <table class="table">
                <thead>
                    <tr>
                        <th th:text="#{admin.members.column.twitch_user}">Utilisateur Twitch</th>
                        <th th:text="#{admin.members.column.steam_id}">Steam ID</th>
                        <th th:text="#{admin.members.column.bot}">Bot</th>
                        <th th:text="#{admin.members.column.status}">Statut</th>
                        <th th:text="#{admin.members.column.created_at}">Créé le</th>
                        <th th:if="${canMockSteam}" th:text="#{admin.members.column.mock_steam}">Mock Steam</th>
                        <th th:text="#{admin.members.column.actions}">Actions</th>
                    </tr>
                </thead>
                <tbody>
                    <tr th:each="member : ${members}">
                        <td>
                            <strong th:text="${member.twitchUsername}"></strong><br>
                            <span class="badge" th:text="${member.twitchId}"></span>
                        </td>
                        <td th:text="${member.steamId != null ? member.steamId : '—'}"></td>
                        <td>
                            <form th:action="@{/admin/members/{id}/bot/toggle(id=${member.id})}" method="post"> <!-- nosemgrep -->
                                <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
                                <div class="pill-toggle"
                                     th:classappend="${member.botEnabled ? 'pill-toggle--active' : 'pill-toggle--inactive'}">
                                    <span class="pill-toggle__label"
                                          th:text="${member.botEnabled ? #messages.msg('admin.members.bot.active') : #messages.msg('admin.members.bot.inactive')}"></span>
                                    <button type="submit" class="pill-toggle__btn"
                                            th:text="${member.botEnabled ? #messages.msg('admin.members.bot.disable') : #messages.msg('admin.members.bot.enable')}"></button>
                                </div>
                            </form>
                        </td>
                        <td>
                            <th:block th:unless="${isMockProfile}">
                                <span th:if="${member.status.name() == 'ACTIVE'}" class="badge badge-live" th:text="${member.status}"></span>
                                <span th:unless="${member.status.name() == 'ACTIVE'}" class="badge" th:text="${member.status}"></span>
                            </th:block>
                            <th:block th:if="${isMockProfile}">
                                <form th:if="${liveStatus[member.id]}"
                                      th:action="@{/admin/members/{id}/twitch/offline(id=${member.id})}" method="post"> <!-- nosemgrep -->
                                    <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
                                    <div class="pill-toggle pill-toggle--live">
                                        <span class="pill-toggle__label">🔴 Live</span>
                                        <button type="submit" class="pill-toggle__btn">■ Offline</button>
                                    </div>
                                </form>
                                <form th:unless="${liveStatus[member.id]}"
                                      th:action="@{/admin/members/{id}/twitch/online(id=${member.id})}" method="post"> <!-- nosemgrep -->
                                    <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
                                    <div class="pill-toggle pill-toggle--offline">
                                        <span class="pill-toggle__label">⬛ Offline</span>
                                        <button type="submit" class="pill-toggle__btn">▶ Online</button>
                                    </div>
                                </form>
                            </th:block>
                        </td>
                        <td th:text="${member.createdAt}"></td>
                        <td th:if="${canMockSteam}">
                            <div th:if="${member.steamId != null}" style="display:flex;gap:6px;align-items:flex-end">
                                <form th:action="@{/admin/members/{id}/steam/set(id=${member.id})}" method="post"> <!-- nosemgrep -->
                                    <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
                                    <div class="search-wrapper">
                                        <input type="text"
                                               th:id="'steam-search-' + ${member.id}"
                                               th:placeholder="#{common.search_placeholder}"
                                               oninput="gameSearch(event)"
                                               autocomplete="off"
                                               th:attr="data-results-id='steam-results-' + ${member.id},
                                                        data-gameid-field='steam-gameId-' + ${member.id},
                                                        data-gamename-field='steam-gameName-' + ${member.id}"
                                               data-search-url="/admin/members/igdb/search"
                                               style="width:180px;padding:2px 4px">
                                        <ul th:id="'steam-results-' + ${member.id}" class="game-results"></ul>
                                    </div>
                                    <input type="hidden" name="gameId"   th:id="'steam-gameId-'   + ${member.id}" required>
                                    <input type="hidden" name="gameName" th:id="'steam-gameName-' + ${member.id}" required>
                                    <button type="submit" class="btn btn-sm btn-primary">Set</button>
                                </form>
                                <form th:action="@{/admin/members/{id}/steam/clear(id=${member.id})}" method="post"> <!-- nosemgrep -->
                                    <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
                                    <button type="submit" class="btn btn-sm">Clear</button>
                                </form>
                            </div>
                            <span th:if="${member.steamId == null}">—</span>
                        </td>
                        <td>
                            <form th:action="@{/admin/impersonate}" method="post"> <!-- nosemgrep -->
                                <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
                                <input type="hidden" name="username" th:value="${member.twitchUsername}"/>
                                <button type="submit" class="btn btn-sm" th:disabled="${member.twitchId == currentUserTwitchId}">Impersonate</button>
                            </form>
                        </td>
                    </tr>
                </tbody>
            </table>
        </div>
    </div>
</body>
</html>
```

Note : `style="max-width: none"` était sur le `<main>` originel. Cette contrainte est maintenant perdue car `main_class='container'` ne supporte pas d'attributs inline supplémentaires. Si ce style est nécessaire visuellement, ajouter une classe CSS dédiée dans `app.css` pour cette page.

- [ ] **Étape 2 : Commit**

```bash
git add src/main/resources/templates/admin/members.html
git commit -m "refactor: migrate admin/members to template layout"
```

---

### Task 4 — Migrer `admin/global-process-rules.html`

**Files:**
- Modify: `src/main/resources/templates/admin/global-process-rules.html`

- [ ] **Étape 1 : Réécrire `admin/global-process-rules.html`**

On retire le head inline, la nav. Pas d'`additional_head` (pas de styles inline). Le script de recherche reste dans le body.

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
      th:replace="~{template::layout(~{::title}, 'process-rules', ~{::body}, null, 'container')}">
<head>
    <title>Catapult — Règles globales processus</title>
</head>
<body>
    <h2 th:text="#{admin.process_rules.title}">Administration — Règles processus globales</h2>
    <p class="text-muted" style="margin-bottom:1.5rem" th:utext="#{admin.process_rules.description}">
        Ces règles s'appliquent à <strong>tous les utilisateurs</strong> en fallback,
        si l'utilisateur n'a pas de binding personnel pour le processus détecté.
    </p>

    <div th:if="${error}" style="background:#fee;border:1px solid #fcc;padding:10px 14px;border-radius:4px;margin-bottom:1rem;color:#c00"
         th:text="${error}"></div>

    <div th:if="${globalRules.empty}" class="text-muted" style="margin-bottom:1.5rem" th:text="#{admin.process_rules.empty}">
        Aucune règle globale configurée.
    </div>

    <div th:unless="${globalRules.empty}" style="margin-bottom:1.5rem">
        <table style="width:100%;border-collapse:collapse">
            <thead>
                <tr style="border-bottom:2px solid #eee;text-align:left">
                    <th style="padding:8px" th:text="#{admin.process_rules.column.pattern}">Pattern processus</th>
                    <th style="padding:8px" th:text="#{admin.process_rules.column.regex}">Regex</th>
                    <th style="padding:8px" th:text="#{admin.process_rules.column.game}">Jeu Twitch</th>
                    <th style="padding:8px" th:text="#{admin.process_rules.column.predicates}">Prédicats</th>
                    <th style="padding:8px"></th>
                </tr>
            </thead>
            <tbody>
                <tr th:each="rule : ${globalRules}" style="border-bottom:1px solid #f0f0f0;vertical-align:top">
                    <td style="padding:8px"><code th:text="${rule.processName}"></code></td>
                    <td style="padding:8px">
                        <span th:if="${rule.regex}"
                              style="background:#e8f4fd;color:#1a7abf;padding:2px 7px;border-radius:3px;font-size:.8em;font-weight:600">regex</span>
                        <span th:unless="${rule.regex}" style="color:#bbb;font-size:.85em">exact</span>
                    </td>
                    <td style="padding:8px" th:text="${rule.twitchGameName}"></td>

                    <td style="padding:8px;min-width:320px">
                        <div th:unless="${rule.predicates.empty}" style="margin-bottom:6px">
                            <table style="width:100%;font-size:.8em;border-collapse:collapse">
                                <thead>
                                    <tr style="border-bottom:1px solid #eee;color:#888">
                                        <th style="padding:4px" th:text="#{obs.predicate.column.link}">Lien</th>
                                        <th style="padding:4px" th:text="#{obs.predicate.column.type}">Type</th>
                                        <th style="padding:4px" th:text="#{obs.predicate.column.key}">Clé</th>
                                        <th style="padding:4px" th:text="#{obs.predicate.column.value}">Valeur</th>
                                        <th style="padding:4px" th:text="#{obs.predicate.column.os}">OS</th>
                                        <th style="padding:4px"></th>
                                    </tr>
                                </thead>
                                <tbody>
                                    <tr th:each="pred, iterStat : ${rule.predicates}" style="border-bottom:1px solid #f5f5f5">
                                        <td style="padding:4px;color:#888">
                                            <span th:if="${iterStat.first}">—</span>
                                            <span th:unless="${iterStat.first}" th:text="${pred.connector}"></span>
                                        </td>
                                        <td style="padding:4px" th:text="${pred.type}"></td>
                                        <td style="padding:4px"><code th:text="${pred.key}"></code></td>
                                        <td style="padding:4px"><code th:text="${pred.value}"></code></td>
                                        <td style="padding:4px" th:text="${pred.osTarget}"></td>
                                        <td style="padding:4px">
                                            <form th:action="@{/admin/process-rules/{id}/predicates/{predId}/delete(id=${rule.id},predId=${pred.id})}" method="post">
                                                <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
                                                <button type="submit" class="btn btn-sm btn-danger" style="padding:2px 6px;font-size:.75em">✕</button>
                                            </form>
                                        </td>
                                    </tr>
                                </tbody>
                            </table>
                        </div>

                        <form th:action="@{/admin/process-rules/{id}/predicates(id=${rule.id})}" method="post"
                              style="display:grid;grid-template-columns:auto auto 1fr auto auto;gap:4px;align-items:end;font-size:.82em">
                            <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
                            <div style="display:flex;flex-direction:column;gap:2px">
                                <label style="color:#777;font-weight:600" th:text="#{obs.predicate.form.link}">Lien</label>
                                <select name="connector" style="padding:4px 6px;border:1px solid #ccc;border-radius:4px">
                                    <option th:each="c : ${connectors}" th:value="${c}" th:text="${c}"></option>
                                </select>
                            </div>
                            <div style="display:flex;flex-direction:column;gap:2px">
                                <label style="color:#777;font-weight:600" th:text="#{obs.predicate.form.type}">Type</label>
                                <select name="type" style="padding:4px 6px;border:1px solid #ccc;border-radius:4px">
                                    <option th:each="t : ${predicateTypes}" th:value="${t}" th:text="${t}"></option>
                                </select>
                            </div>
                            <div style="display:flex;flex-direction:column;gap:2px">
                                <label style="color:#777;font-weight:600" th:text="#{obs.predicate.form.value_label}">Valeur</label>
                                <input type="text" name="value" required th:placeholder="#{obs.predicate.form.value_placeholder}"
                                       style="padding:4px 8px;border:1px solid #ccc;border-radius:4px;width:100%">
                            </div>
                            <div style="display:flex;flex-direction:column;gap:2px">
                                <label style="color:#777;font-weight:600" th:text="#{obs.predicate.form.key_label}">Clé (ENV_VAR)</label>
                                <input type="text" name="key" th:placeholder="#{obs.predicate.form.key_placeholder}"
                                       style="padding:4px 8px;border:1px solid #ccc;border-radius:4px;width:90px">
                            </div>
                            <div style="display:flex;flex-direction:column;gap:2px">
                                <label style="color:#777;font-weight:600" th:text="#{obs.predicate.form.os}">OS</label>
                                <select name="osTarget" style="padding:4px 6px;border:1px solid #ccc;border-radius:4px">
                                    <option th:each="o : ${osTargets}" th:value="${o}" th:text="${o}"></option>
                                </select>
                            </div>
                            <button type="submit" class="btn btn-sm btn-primary" style="align-self:end" th:text="'+ ' + #{common.add}">+ Ajouter</button>
                        </form>
                    </td>

                    <td style="padding:8px;text-align:right">
                        <form th:action="@{/admin/process-rules/{id}/delete(id=${rule.id})}" method="post">
                            <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
                            <button type="submit" class="btn btn-sm btn-danger" th:text="#{common.delete}">Supprimer</button>
                        </form>
                    </td>
                </tr>
            </tbody>
        </table>
    </div>

    <div class="card" style="padding:1.5rem">
        <h4 style="margin-top:0;margin-bottom:1rem" th:text="#{admin.process_rules.add.title}">Ajouter une règle</h4>
        <p class="text-muted" style="font-size:.85em;margin-bottom:1rem" th:utext="#{admin.process_rules.add.note}">
            <strong>Note :</strong> le pattern est comparé au nom du processus tel que reçu par OBS.
        </p>
        <form th:action="@{/admin/process-rules}" method="post"
              style="display:flex;gap:12px;align-items:flex-end;flex-wrap:wrap">
            <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
            <div style="display:flex;flex-direction:column;gap:4px">
                <label style="font-size:.8em;font-weight:600;color:#555" th:text="#{admin.process_rules.add.process_label}">Pattern processus</label>
                <input type="text" name="processName" th:placeholder="#{admin.process_rules.add.process_placeholder}" required
                       style="padding:6px 10px;border:1px solid #ccc;border-radius:4px;font-size:.9em;width:220px">
            </div>
            <div style="display:flex;flex-direction:column;gap:4px">
                <label style="font-size:.8em;font-weight:600;color:#555" th:text="#{admin.process_rules.add.regex_label}">Regex</label>
                <label style="display:flex;align-items:center;gap:6px;padding:7px 0">
                    <input type="checkbox" name="isRegex" value="true">
                    <span style="font-size:.9em" th:text="#{admin.process_rules.add.regex_checkbox}">Pattern regex</span>
                </label>
            </div>
            <div style="display:flex;flex-direction:column;gap:4px;position:relative">
                <label style="font-size:.8em;font-weight:600;color:#555" th:text="#{admin.process_rules.add.game_label}">Jeu Twitch</label>
                <div class="search-wrapper">
                    <input type="text" id="adminGameSearch" th:placeholder="#{admin.process_rules.add.game_placeholder}"
                           oninput="adminSearch(event)" autocomplete="off"
                           style="padding:6px 10px;border:1px solid #ccc;border-radius:4px;font-size:.9em;width:220px">
                    <ul id="gameResults-admin" class="game-results"></ul>
                </div>
                <input type="hidden" name="twitchGameId" id="twitchGameId-admin" required>
                <input type="hidden" name="twitchGameName" id="twitchGameName-admin">
            </div>
            <button type="submit" class="btn btn-primary" th:text="#{common.add}">Ajouter</button>
        </form>
    </div>

    <script>
    (function() {
        var _timer = null;
        window.adminSearch = function(event) {
            var q = event.target.value.trim();
            var results = document.getElementById('gameResults-admin');
            if (q.length < 2) { results.style.display = 'none'; return; }
            clearTimeout(_timer);
            _timer = setTimeout(function() {
                fetch('/api/games/search?q=' + encodeURIComponent(q))
                    .then(function(r) { return r.json(); })
                    .then(function(data) {
                        results.replaceChildren();
                        if (!data.length) { results.style.display = 'none'; return; }
                        data.forEach(function(game) {
                            var li = document.createElement('li');
                            if (game.boxArtUrl) {
                                var img = document.createElement('img');
                                img.src = game.boxArtUrl.replace('{width}', '30').replace('{height}', '40');
                                img.alt = '';
                                li.appendChild(img);
                            }
                            var span = document.createElement('span');
                            span.textContent = game.name;
                            li.appendChild(span);
                            li.addEventListener('click', function() {
                                document.getElementById('twitchGameId-admin').value = game.id;
                                document.getElementById('twitchGameName-admin').value = game.name;
                                document.getElementById('adminGameSearch').value = game.name;
                                results.style.display = 'none';
                            });
                            results.appendChild(li);
                        });
                        results.style.display = 'block';
                    })
                    .catch(function() { results.style.display = 'none'; });
            }, 300);
        };
    })();
    </script>
</body>
</html>
```

- [ ] **Étape 2 : Commit**

```bash
git add src/main/resources/templates/admin/global-process-rules.html
git commit -m "refactor: migrate admin/global-process-rules to template layout"
```

---

### Task 5 — Migrer `dev/igdb-explorer.html`

**Files:**
- Modify: `src/main/resources/templates/dev/igdb-explorer.html`

Cette page est la plus complexe : layout full-height (`main_class=''`), CSS inline dans `additional_head`, et CSRF géré via Thymeleaf inline dans le script (remplace les meta tags).

- [ ] **Étape 1 : Réécrire `dev/igdb-explorer.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
      th:replace="~{template::layout(~{::title}, 'igdb', ~{::body}, ~{::style}, '')}">
<head>
    <title>Catapult — IGDB Explorer</title>
    <style>
        .explorer-layout {
            display: flex;
            gap: 0;
            height: calc(100vh - 56px);
            overflow: hidden;
        }
        .endpoint-sidebar {
            width: 200px;
            flex-shrink: 0;
            border-right: 1px solid #2d2f3e;
            background: #13151f;
            overflow-y: auto;
            padding: 12px 0;
        }
        .endpoint-sidebar h3 {
            font-size: 11px;
            font-weight: 600;
            text-transform: uppercase;
            letter-spacing: .08em;
            color: #64748b;
            padding: 0 12px 8px;
        }
        .endpoint-btn {
            display: block;
            width: 100%;
            text-align: left;
            background: none;
            border: none;
            color: #94a3b8;
            font-family: ui-monospace, monospace;
            font-size: 12px;
            padding: 5px 12px;
            cursor: pointer;
            transition: background .1s, color .1s;
        }
        .endpoint-btn:hover { background: #1e2133; color: #e2e8f0; }
        .endpoint-btn.active { background: #2d1f5e; color: #a78bfa; font-weight: 600; }

        .query-panel {
            flex: 1;
            display: flex;
            flex-direction: column;
            overflow: hidden;
            background: #0f1117;
        }
        .query-editor {
            border-bottom: 1px solid #2d2f3e;
            padding: 16px;
            display: flex;
            flex-direction: column;
            gap: 10px;
            background: #13151f;
        }
        .endpoint-row {
            display: flex;
            align-items: center;
            gap: 8px;
        }
        .endpoint-label {
            font-size: 11px;
            font-weight: 600;
            text-transform: uppercase;
            letter-spacing: .06em;
            color: #64748b;
            white-space: nowrap;
        }
        .endpoint-input {
            font-family: ui-monospace, monospace;
            font-size: 13px;
            padding: 4px 10px;
            background: #0f1117;
            border: 1px solid #2d2f3e;
            border-radius: 4px;
            color: #a78bfa;
            width: 220px;
        }
        .endpoint-input:focus { outline: none; border-color: #7c3aed; }
        .query-textarea {
            font-family: ui-monospace, monospace;
            font-size: 13px;
            padding: 10px 12px;
            background: #0f1117;
            border: 1px solid #2d2f3e;
            border-radius: 4px;
            color: #e2e8f0;
            resize: vertical;
            min-height: 110px;
            line-height: 1.6;
        }
        .query-textarea:focus { outline: none; border-color: #7c3aed; }
        .editor-actions {
            display: flex;
            align-items: center;
            gap: 10px;
        }
        .btn-run {
            background: #7c3aed;
            color: #fff;
            border: none;
            border-radius: 4px;
            padding: 6px 16px;
            font-size: 13px;
            font-weight: 600;
            cursor: pointer;
            transition: background .15s;
        }
        .btn-run:hover { background: #6d28d9; }
        .htmx-indicator { color: #64748b; font-size: 12px; display: none; }
        .htmx-request .htmx-indicator { display: inline; }
        .results-panel {
            flex: 1;
            overflow: auto;
            padding: 16px;
        }
        .igdb-result {
            margin: 0;
            white-space: pre-wrap;
            word-break: break-word;
            font-family: ui-monospace, monospace;
            font-size: 12px;
            line-height: 1.7;
        }
        .igdb-result--ok { color: #86efac; }
        .igdb-result--error { color: #f87171; }
        .placeholder { color: #475569; font-size: 13px; }
    </style>
</head>
<body>
<div class="explorer-layout">
    <aside class="endpoint-sidebar">
        <h3>Endpoints</h3>
        <button class="endpoint-btn active"
                th:each="ep, stat : ${endpoints}"
                th:classappend="${stat.first} ? 'active' : ''"
                th:data-endpoint="${ep}"
                th:text="${ep}"
                th:onclick="|selectEndpoint(this)|">
        </button>
    </aside>

    <div class="query-panel">
        <form class="query-editor"
              id="query-form"
              hx-post="/dev/igdb/query"
              hx-target="#results"
              hx-indicator="#spinner">
            <div class="endpoint-row">
                <span class="endpoint-label">POST /v4/</span>
                <input type="text" name="endpoint" id="endpoint-input"
                       class="endpoint-input"
                       value="games"
                       autocomplete="off"
                       spellcheck="false"/>
            </div>
            <textarea name="query" id="query-input" class="query-textarea"
                      spellcheck="false">fields id, name, summary, rating;
limit 5;</textarea>
            <div class="editor-actions">
                <button type="submit" class="btn-run" th:text="#{dev.igdb.run}">▶ Exécuter</button>
                <span id="spinner" class="htmx-indicator" th:text="#{dev.igdb.loading}">Chargement…</span>
            </div>
        </form>

        <div class="results-panel" id="results">
            <span class="placeholder" th:text="#{dev.igdb.placeholder}">← Sélectionne un endpoint et exécute une requête</span>
        </div>
    </div>
</div>

<script th:inline="javascript">
    const CSRF_TOKEN = /*[[${_csrf.token}]]*/ '';
    const CSRF_HEADER = /*[[${_csrf.headerName}]]*/ 'X-CSRF-TOKEN';

    const EXAMPLE_QUERIES = {
        games:                       'fields id, name, summary, rating;\nlimit 5;',
        platforms:                   'fields id, name, abbreviation;\nlimit 10;',
        genres:                      'fields id, name;\nlimit 20;',
        themes:                      'fields id, name;\nlimit 20;',
        keywords:                    'fields id, name;\nlimit 10;',
        external_games:              'fields uid, game.name, external_game_source.name;\nlimit 5;',
        external_game_sources:       'fields id, name;\nlimit 50;',
        involved_companies:          'fields company.name, developer, publisher;\nlimit 10;',
        companies:                   'fields id, name, country;\nlimit 10;',
        age_ratings:                 'fields id, category, rating, rating_content_descriptions.description;\nlimit 5;',
        age_rating_content_descriptions: 'fields id, description;\nlimit 20;',
        game_modes:                  'fields id, name;\nlimit 20;',
        player_perspectives:         'fields id, name;\nlimit 20;',
        covers:                      'fields id, url, game.name;\nlimit 5;',
        artworks:                    'fields id, url, game.name;\nlimit 5;',
        screenshots:                 'fields id, url, game.name;\nlimit 5;',
        websites:                    'fields id, url, category, game.name;\nlimit 5;',
        franchises:                  'fields id, name;\nlimit 10;',
        collections:                 'fields id, name, games.name;\nlimit 5;',
        characters:                  'fields id, name, games.name;\nlimit 5;',
        game_engines:                'fields id, name, companies.name;\nlimit 10;',
        languages:                   'fields id, name, native_name;\nlimit 20;',
        language_supports:           'fields id, game.name, language.name;\nlimit 5;',
        multiplayer_modes:           'fields id, game.name, onlinemax, offlinemax;\nlimit 5;',
        platform_families:           'fields id, name;\nlimit 20;',
        release_dates:               'fields id, game.name, platform.name, date;\nlimit 5;',
        videos:                      'fields id, name, video_id, game.name;\nlimit 5;',
    };

    function selectEndpoint(btn) {
        document.querySelectorAll('.endpoint-btn').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
        const ep = btn.dataset.endpoint;
        document.getElementById('endpoint-input').value = ep;
        document.getElementById('query-input').value =
            EXAMPLE_QUERIES[ep] || 'fields *;\nlimit 5;';
    }

    document.addEventListener('htmx:configRequest', function(e) {
        e.detail.headers[CSRF_HEADER] = CSRF_TOKEN;
    });

    document.getElementById('query-form').addEventListener('keydown', function(e) {
        if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
            e.preventDefault();
            htmx.trigger(this, 'submit');
        }
    });
</script>
</body>
</html>
```

Points clés :
- `main_class=''` → pas de wrapper `<main>`, le `.explorer-layout` s'étend directement dans le body
- `additional_head = ~{::style}` → le `<style>` est injecté dans le `<head>` de template.html
- `<script th:inline="javascript">` + `/*[[${_csrf.token}]]*/` remplace les meta tags CSRF

- [ ] **Étape 2 : Commit**

```bash
git add src/main/resources/templates/dev/igdb-explorer.html
git commit -m "refactor: migrate dev/igdb-explorer to template layout"
```

---

### Task 6 — Migrer `app.html`

**Files:**
- Modify: `src/main/resources/templates/app.html`

La migration la plus complexe : supprimer la bannière dupliquée (déjà dans template.html), supprimer la nav, supprimer le wrapper `<main>`. Les styles inline deviennent `additional_head`.

- [ ] **Étape 1 : Réécrire `app.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" xmlns:sec="http://www.thymeleaf.org/extras/springsecurity6"
      th:replace="~{template::layout(~{::title}, 'app', ~{::body}, ~{::style}, 'container')}">
<head>
    <title>Catapult</title>
    <style>
        .toggle-label { display:inline-flex; align-items:center; cursor:pointer; gap:6px; }
        .toggle-label input[type=checkbox] { display:none; }
        .toggle-slider {
            width:36px; height:20px; background:#ccc; border-radius:10px;
            position:relative; transition:background .2s;
        }
        .toggle-slider::after {
            content:''; position:absolute; top:2px; left:2px;
            width:16px; height:16px; background:#fff; border-radius:50%;
            transition:left .2s;
        }
        .toggle-label input:checked + .toggle-slider { background:var(--color-primary,#7c3aed); }
        .toggle-label input:checked + .toggle-slider::after { left:18px; }
        .edit-row td { background:#f9f9f9; padding:12px 16px; }
        .inline-edit-fields { display:flex; flex-wrap:wrap; gap:16px; align-items:flex-start; }
        .inline-edit-field { display:flex; flex-direction:column; gap:4px; }
        .inline-edit-field label { font-size:.8em; font-weight:600; color:#555; }
        .ccl-checkboxes { display:flex; flex-wrap:wrap; gap:8px; }
        .inline-edit-actions { display:flex; gap:8px; margin-top:12px; }
    </style>
</head>
<body>
    <div th:replace="~{fragments/connections :: connections}"></div>
    <div th:if="${obsEnabled}" hx-get="/fragments/obs-setup" hx-trigger="load" hx-swap="outerHTML">
        <div class="card"><p class="text-muted" style="padding:16px" th:text="#{app.loading_obs}">Chargement OBS…</p></div>
    </div>
    <div hx-get="/fragments/no-game-settings" hx-trigger="load" hx-swap="outerHTML">
        <div class="card"><p class="text-muted" style="padding:16px" th:text="#{app.loading}">Chargement…</p></div>
    </div>
    <div th:replace="~{fragments/status :: status}"></div>
    <div th:replace="~{fragments/activity-log :: activity-log}"></div>
    <div th:replace="~{fragments/bindings :: bindings}"></div>
    <div th:replace="~{fragments/bot :: bot}"></div>
    <div th:replace="~{fragments/danger-zone :: danger-zone}"></div>

    <!-- Modal suppression -->
    <div id="deleteModal" class="modal" style="display:none">
        <div class="modal-content">
            <h3 th:text="#{app.modal.title}">Confirmer la suppression</h3>
            <p th:text="#{app.modal.prompt}">Saisis ton nom d'utilisateur Twitch pour confirmer :</p>
            <form th:action="@{/settings/delete-account}" method="post">
                <input type="text" name="confirmUsername" th:placeholder="#{app.modal.username_placeholder}" required>
                <div class="modal-actions">
                    <button type="button"
                            onclick="document.getElementById('deleteModal').style.display='none'"
                            class="btn btn-outline" th:text="#{common.cancel}">Annuler</button>
                    <button type="submit" class="btn btn-danger" th:text="#{app.modal.confirm_button}">Supprimer définitivement</button>
                </div>
            </form>
        </div>
    </div>

    <script>
        // Bindings inline edit
        const debounceTimers = {};
        function debouncedSearch(event, id) {
            clearTimeout(debounceTimers[id]);
            const q = event.target.value.trim();
            const results = document.getElementById('gameResults-' + id);
            if (q.length < 2) { results.style.display = 'none'; return; }
            debounceTimers[id] = setTimeout(() => fetchSuggestions(q, id), 300);
        }
        function fetchSuggestions(q, id) {
            fetch('/api/games/search?q=' + encodeURIComponent(q))
                .then(r => r.json())
                .then(data => {
                    const results = document.getElementById('gameResults-' + id);
                    results.replaceChildren();
                    if (!data.length) { results.style.display = 'none'; return; }
                    data.forEach(game => {
                        const li = document.createElement('li');
                        if (game.boxArtUrl) {
                            const img = document.createElement('img');
                            img.src = game.boxArtUrl.replace('{width}', '30').replace('{height}', '40');
                            img.alt = '';
                            li.appendChild(img);
                        }
                        const span = document.createElement('span');
                        span.textContent = game.name;
                        li.appendChild(span);
                        li.addEventListener('click', () => {
                            document.getElementById('twitchGameId-' + id).value = game.id;
                            document.getElementById('twitchGameName-' + id).value = game.name;
                            document.getElementById('gameSearch-' + id).value = game.name;
                            results.style.display = 'none';
                        });
                        results.appendChild(li);
                    });
                    results.style.display = 'block';
                })
                .catch(() => document.getElementById('gameResults-' + id).style.display = 'none');
        }
        document.addEventListener('click', e => {
            document.querySelectorAll('.game-results').forEach(el => {
                if (!el.previousElementSibling?.contains(e.target)) el.style.display = 'none';
            });
        });

        // Activity log SSE
        (function () {
            const list = document.getElementById('log-list');
            const placeholder = document.getElementById('log-placeholder');
            const MAX_LINES = 50;
            const es = new EventSource('/app/logs');
            es.onmessage = function (e) {
                if (placeholder) placeholder.remove();
                const li = document.createElement('li');
                li.textContent = e.data;
                li.style.borderBottom = '1px solid #eee';
                li.style.padding = '2px 0';
                list.appendChild(li);
                while (list.children.length > MAX_LINES) list.removeChild(list.firstChild);
            };
            es.onerror = function () { es.close(); };
        })();
    </script>
</body>
</html>
```

- [ ] **Étape 2 : Commit**

```bash
git add src/main/resources/templates/app.html
git commit -m "refactor: migrate app.html to template layout, remove duplicate banner/nav"
```

---

### Task 7 — Supprimer les fragments orphelins

**Files:**
- Delete: `src/main/resources/templates/fragments/head.html`
- Delete: `src/main/resources/templates/fragments/nav.html`

- [ ] **Étape 1 : Vérifier qu'aucune page ne référence encore ces fragments**

```bash
grep -r "fragments/head\|fragments/nav" src/main/resources/templates/
```
Attendu : aucun résultat.

- [ ] **Étape 2 : Supprimer les fichiers**

```bash
git rm src/main/resources/templates/fragments/head.html
git rm src/main/resources/templates/fragments/nav.html
```

- [ ] **Étape 3 : Commit**

```bash
git commit -m "chore: remove orphaned fragments/head.html and fragments/nav.html"
```

---

## Notes post-implémentation

- Le `style="max-width: none"` qui était sur le `<main>` de `admin/members.html` est perdu. Si la page se coupe visuellement, ajouter `.members-main { max-width: none; }` dans `app.css` et l'appliquer via un wrapper div dans le body.
- `th:include` reste déprécié mais fonctionnel. Sa suppression propre nécessite un refactoring du passage de `~{::body}` vers un fragment nommé — reporté à un futur chantier.
- Les scripts page-spécifiques (`<script>` en fin de body) atterrissent à l'intérieur du `<main class="container">` après inclusion via `th:include`. Cela est fonctionnel pour les navigateurs.
