# Pill Toggle Button Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fusionne les badges de statut et les boutons d'action dans la table admin/membres en un seul élément pill à deux segments.

**Architecture:** Pure CSS + Thymeleaf HTML change. Nouvelles classes `.pill-toggle` dans `app.css`, refactoring de deux colonnes (`Bot`, `Statut`) dans `admin/members.html`. Aucun changement de logique Java/backend.

**Tech Stack:** Thymeleaf, CSS (tokens CSS variables existants), Spring Boot (build Gradle)

---

## Fichiers modifiés

| Fichier | Changement |
|---------|------------|
| `src/main/resources/static/css/app.css` | Nouvelles classes `.pill-toggle` + override `old-steam` |
| `src/main/resources/templates/admin/members.html` | Colonnes Bot (lignes 35-41) et Statut (lignes 43-55) |

---

## Task 1 : Ajouter les classes CSS `.pill-toggle`

**Files:**
- Modify: `src/main/resources/static/css/app.css`

- [ ] **Step 1 : Ajouter le bloc pill-toggle après la section Buttons dans `app.css`**

Ajouter après la ligne `.btn-link:hover { color: var(--text); }` :

```css
/* ============================================================
   Pill toggle (status + action in one element)
   ============================================================ */
.pill-toggle {
    display: inline-flex;
    border-radius: 9999px;
    overflow: hidden;
    border: 1px solid transparent;
    font-size: 12px;
    font-weight: 600;
    white-space: nowrap;
}

.pill-toggle__label {
    padding: 4px 12px;
    display: flex;
    align-items: center;
}

.pill-toggle__btn {
    padding: 4px 12px;
    border: none;
    border-left: 1px solid rgba(0, 0, 0, 0.15);
    cursor: pointer;
    font-size: inherit;
    font-weight: inherit;
    font-family: inherit;
    background: inherit;
    filter: brightness(0.85);
    color: inherit;
    transition: opacity .15s;
}

.pill-toggle__btn:hover { opacity: .85; }

.pill-toggle--active  { background: var(--success); color: var(--success-text); border-color: var(--success); }
.pill-toggle--inactive { background: var(--muted);  color: var(--text-muted);   border-color: var(--muted); }
.pill-toggle--live    { background: var(--danger);  color: #fff;                border-color: var(--danger); }
.pill-toggle--offline { background: var(--muted);   color: var(--text-muted);   border-color: var(--muted); }
```

- [ ] **Step 2 : Ajouter l'override `old-steam` à la fin du fichier**

Ajouter à la liste des sélecteurs `border-radius: 0` existante (section `old-steam — border style overrides`), après `[data-theme="old-steam"] .btn-run { border-radius: 0; }` :

```css
[data-theme="old-steam"] .pill-toggle,
[data-theme="old-steam"] .pill-toggle__btn { border-radius: 0; }
```

- [ ] **Step 3 : Vérifier la syntaxe CSS via build**

```bash
./gradlew build -x test
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4 : Commit**

```bash
git add src/main/resources/static/css/app.css
git commit -m "feat: add pill-toggle CSS component"
```

---

## Task 2 : Refactorer la colonne Bot dans `admin/members.html`

**Files:**
- Modify: `src/main/resources/templates/admin/members.html` (lines 35–41)

- [ ] **Step 1 : Remplacer le contenu de la `<td>` Bot**

Remplacer le bloc :
```html
<td>
    <span th:if="${member.botEnabled}" class="badge badge-live">✓</span>
    <span th:unless="${member.botEnabled}" class="badge">✗</span>
    <form th:action="@{/admin/members/{id}/bot/toggle(id=${member.id})}" method="post" style="display:inline;margin-left:4px"> <!-- nosemgrep -->
        <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
        <button type="submit" class="btn btn-sm" th:text="${member.botEnabled ? 'Désactiver' : 'Activer'}"></button>
    </form>
</td>
```

Par :
```html
<td>
    <form th:action="@{/admin/members/{id}/bot/toggle(id=${member.id})}" method="post"> <!-- nosemgrep -->
        <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
        <div class="pill-toggle"
             th:classappend="${member.botEnabled ? 'pill-toggle--active' : 'pill-toggle--inactive'}">
            <span class="pill-toggle__label"
                  th:text="${member.botEnabled ? '✓ Actif' : '✗ Inactif'}"></span>
            <button type="submit" class="pill-toggle__btn"
                    th:text="${member.botEnabled ? 'Désactiver' : 'Activer'}"></button>
        </div>
    </form>
</td>
```

- [ ] **Step 2 : Build pour vérifier le template Thymeleaf**

```bash
./gradlew build -x test
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3 : Commit**

```bash
git add src/main/resources/templates/admin/members.html
git commit -m "feat: refactor bot column to pill-toggle in members table"
```

---

## Task 3 : Refactorer la colonne Statut dans `admin/members.html`

**Files:**
- Modify: `src/main/resources/templates/admin/members.html` (lines 43–55)

- [ ] **Step 1 : Remplacer le contenu de la `<td>` Statut**

Remplacer le bloc :
```html
<td>
    <span th:if="${member.status.name() == 'ACTIVE'}" class="badge badge-live" th:text="${member.status}"></span>
    <span th:unless="${member.status.name() == 'ACTIVE'}" class="badge" th:text="${member.status}"></span>
    <div th:if="${isMockProfile}" style="display:flex;gap:6px;margin-top:4px">
        <form th:action="@{/admin/members/{id}/twitch/online(id=${member.id})}" method="post"> <!-- nosemgrep -->
            <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
            <button type="submit" class="btn btn-danger btn-sm" th:disabled="${liveStatus[member.id]}">▶ Online</button>
        </form>
        <form th:action="@{/admin/members/{id}/twitch/offline(id=${member.id})}" method="post"> <!-- nosemgrep -->
            <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
            <button type="submit" class="btn btn-sm" th:disabled="${!liveStatus[member.id]}">■ Offline</button>
        </form>
    </div>
</td>
```

Par :
```html
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
```

- [ ] **Step 2 : Build complet avec tests**

```bash
./gradlew build
```

Expected: `BUILD SUCCESSFUL` — tous les tests existants passent.

- [ ] **Step 3 : Commit**

```bash
git add src/main/resources/templates/admin/members.html
git commit -m "feat: refactor statut column to pill-toggle in members table"
```
