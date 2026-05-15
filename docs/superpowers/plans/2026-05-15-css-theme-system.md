# CSS Theme System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a client-side CSS theme system with three themes (`dark`, `blanc`, `old-steam`) persisted via `localStorage` and selected via a fixed floating button group.

**Architecture:** All design tokens are already CSS custom properties in `:root`. Two new override blocks (`[data-theme="blanc"]` and `[data-theme="old-steam"]`) are added to `app.css`. A tiny inline script in `head.html` restores the saved theme before CSS renders to prevent flash. A floating 3-button widget in `nav.html` calls `setTheme()` from `app.js`.

**Tech Stack:** CSS custom properties, vanilla JS, Thymeleaf fragments, Spring Boot static resources.

---

## File Map

| File | Change |
|---|---|
| `src/main/resources/static/css/app.css` | Add theme override blocks + floating switcher CSS |
| `src/main/resources/templates/fragments/head.html` | Add inline FOUC-prevention script before `<link>` |
| `src/main/resources/static/js/app.js` | Add `setTheme()` + `initThemeUI()` |
| `src/main/resources/templates/fragments/nav.html` | Add `.theme-switcher` div inside `<nav>` |

---

## Task 1: Add theme variable blocks to `app.css`

**Files:**
- Modify: `src/main/resources/static/css/app.css` (after line 27 — after the closing `}` of `:root`)

- [ ] **Step 1: Add the two theme override blocks**

In `app.css`, immediately after the closing `}` of the `:root` block (after line 27), insert:

```css
/* ============================================================
   Theme: blanc
   ============================================================ */
[data-theme="blanc"] {
    --bg-base:       #ffffff;
    --bg-surface:    #f5f5f5;
    --bg-elevated:   #eeeeee;
    --border:        #e0e0e0;
    --border-subtle: #ebebeb;
    --text:          #1a1a1a;
    --text-muted:    #555555;
    --text-dim:      #888888;
    --text-bright:   #000000;
    --text-subtle:   #333333;
    --accent:        #7c3aed;
    --accent-strong: #6d28d9;
    --accent-deep:   #5b21b6;
    --muted:         #999999;
    --danger:        #b91c1c;
    --danger-bg:     #fef2f2;
    --danger-border: #fecaca;
    --danger-text:   #b91c1c;
    --success:       #22c55e;
    --success-text:  #15803d;
    --twitch:        #9146ff;
    --twitch-dark:   #7d2ff7;
}

/* ============================================================
   Theme: old-steam  (Steam 2003 original palette)
   ============================================================ */
[data-theme="old-steam"] {
    --bg-base:       #293021;
    --bg-surface:    #4c5844;
    --bg-elevated:   #3e4637;
    --border:        #282e22;
    --border-subtle: #323828;
    --text:          #dedfd6;
    --text-muted:    #a0aa95;
    --text-dim:      #889180;
    --text-bright:   #ffffff;
    --text-subtle:   #c6c3b8;
    --accent:        #c6b652;
    --accent-strong: #9a8c38;
    --accent-deep:   #7a6e2a;
    --muted:         #5a6a50;
    --danger:        #b91c1c;
    --danger-bg:     #3b1219;
    --danger-border: #7f1d1d;
    --danger-text:   #fca5a5;
    --success:       #5a9e4e;
    --success-text:  #7aaa5a;
    --twitch:        #9146ff;
    --twitch-dark:   #7d2ff7;
}
```

- [ ] **Step 2: Quick smoke-check in browser DevTools**

Open any page of the app in Firefox/Chrome. In the DevTools console, run:
```js
document.documentElement.setAttribute('data-theme', 'blanc')
```
Expected: page background turns white, text turns dark. Then:
```js
document.documentElement.setAttribute('data-theme', 'old-steam')
```
Expected: page background turns dark greenish-brown, text turns warm off-white.
```js
document.documentElement.removeAttribute('data-theme')
```
Expected: page returns to original dark theme.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/css/app.css
git commit -m "feat: add blanc and old-steam CSS theme variable blocks"
```

---

## Task 2: Add floating theme switcher CSS to `app.css`

**Files:**
- Modify: `src/main/resources/static/css/app.css` (append at end of file)

- [ ] **Step 1: Append switcher styles**

At the very end of `app.css`, append:

```css
/* ============================================================
   Theme switcher (floating, fixed bottom-right)
   ============================================================ */
.theme-switcher {
    position: fixed;
    bottom: 16px;
    right: 16px;
    display: flex;
    gap: 4px;
    background: var(--bg-surface);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 4px;
    z-index: 200;
}

.theme-btn {
    background: none;
    border: none;
    cursor: pointer;
    font-size: 16px;
    padding: 4px 6px;
    border-radius: 4px;
    opacity: 0.5;
    transition: opacity .15s, background .15s;
}

.theme-btn:hover { opacity: 1; }
.theme-btn.active { background: var(--border); opacity: 1; }
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/css/app.css
git commit -m "feat: add floating theme switcher CSS"
```

---

## Task 3: Add FOUC-prevention script to `head.html`

**Files:**
- Modify: `src/main/resources/templates/fragments/head.html`

The current file:
```html
<head th:fragment="head(title)">
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title th:text="${title}">Catapult</title>
    <link rel="stylesheet" th:href="@{/css/app.css}">
    ...
```

- [ ] **Step 1: Insert inline script before the stylesheet link**

Add the inline script between `<title>` and `<link rel="stylesheet">`. The script must run before CSS is applied so the correct `data-theme` is on `<html>` before the cascade resolves.

Result:
```html
<head th:fragment="head(title)">
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title th:text="${title}">Catapult</title>
    <script>(function(){var t=localStorage.getItem('theme');if(t)document.documentElement.setAttribute('data-theme',t);})();</script>
    <link rel="stylesheet" th:href="@{/css/app.css}">
```

- [ ] **Step 2: Verify no FOUC**

Restart the app. Set a theme via DevTools console: `localStorage.setItem('theme','blanc')`. Reload the page. Expected: page loads immediately in blanc theme with no visible flash to dark and back.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/templates/fragments/head.html
git commit -m "feat: add FOUC-prevention theme script to head fragment"
```

---

## Task 4: Add `setTheme()` and `initThemeUI()` to `app.js`

**Files:**
- Modify: `src/main/resources/static/js/app.js` (append at end of file)

- [ ] **Step 1: Append theme management functions**

At the very end of `app.js`, append:

```js
function setTheme(name) {
    if (name === 'dark') {
        document.documentElement.removeAttribute('data-theme');
        localStorage.removeItem('theme');
    } else {
        document.documentElement.setAttribute('data-theme', name);
        localStorage.setItem('theme', name);
    }
    document.querySelectorAll('.theme-btn').forEach(function(b) {
        b.classList.toggle('active', b.dataset.theme === name);
    });
}

function initThemeUI() {
    var current = localStorage.getItem('theme') || 'dark';
    document.querySelectorAll('.theme-btn').forEach(function(b) {
        b.classList.toggle('active', b.dataset.theme === current);
    });
}

document.addEventListener('DOMContentLoaded', initThemeUI);
```

- [ ] **Step 2: Verify in DevTools console**

In the browser console, call:
```js
setTheme('blanc')
```
Expected: page switches to blanc theme, `localStorage.getItem('theme')` returns `'blanc'`.

```js
setTheme('old-steam')
```
Expected: page switches to old-steam theme.

```js
setTheme('dark')
```
Expected: page returns to dark theme, `localStorage.getItem('theme')` returns `null`.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/js/app.js
git commit -m "feat: add setTheme and initThemeUI JS functions"
```

---

## Task 5: Add theme switcher widget to `nav.html`

**Files:**
- Modify: `src/main/resources/templates/fragments/nav.html`

Current structure:
```html
<nav th:fragment="nav(active)" class="navbar">
    <a th:href="@{/app}" class="nav-brand">🚀 Catapult</a>
    <div class="nav-links">
        ...
        <form ...><button ...>Déconnexion</button></form>
    </div>
</nav>
```

The `.theme-switcher` div must be a **last child inside `<nav>`**, not a sibling, because Thymeleaf only includes the fragment element itself. `position: fixed` means it renders bottom-right regardless of DOM position.

- [ ] **Step 1: Add the theme switcher div before the closing `</nav>` tag**

```html
<nav th:fragment="nav(active)" class="navbar">
    <a th:href="@{/app}" class="nav-brand">🚀 Catapult</a>
    <div class="nav-links">
        <a th:href="@{/app}" sec:authorize="hasRole('ADMIN')" th:classappend="${active == 'app'} ? ' active'">App</a>
        <a th:href="@{/admin/ccl}" sec:authorize="hasRole('ADMIN')" th:classappend="${active == 'ccl'} ? ' active'">Admin CCL</a>
        <a th:href="@{/admin/process-rules}" sec:authorize="hasRole('ADMIN')" th:classappend="${active == 'process-rules'} ? ' active'">Règles processus</a>
        <a th:href="@{/dev/igdb}" sec:authorize="hasRole('ADMIN')" th:classappend="${active == 'igdb'} ? ' active'" th:if="${#arrays.contains(@environment.getActiveProfiles(), 'dev')}">IGDB Explorer</a>
        <a th:href="@{/mock/twitch}" sec:authorize="hasRole('ADMIN')" th:classappend="${active == 'twitch'} ? ' active'" th:if="${#arrays.contains(@environment.getActiveProfiles(), 'mock')}">Twitch Mock</a>
        <a th:href="@{/mock/steam}" sec:authorize="hasRole('ADMIN')" th:classappend="${active == 'steam'} ? ' active'" th:if="${#arrays.contains(@environment.getActiveProfiles(), 'mock')}">Steam Mock</a>
        <form th:action="@{/logout}" method="post" style="display:inline">
            <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
            <button type="submit" class="btn-link">Déconnexion</button>
        </form>
    </div>
    <div class="theme-switcher">
        <button class="theme-btn" data-theme="dark"      onclick="setTheme('dark')"      title="Dark">🌙</button>
        <button class="theme-btn" data-theme="blanc"     onclick="setTheme('blanc')"     title="Blanc">☀️</button>
        <button class="theme-btn" data-theme="old-steam" onclick="setTheme('old-steam')" title="Old Steam">🎮</button>
    </div>
</nav>
```

- [ ] **Step 2: Full end-to-end verification**

Start the app and navigate to `/app`:

1. The 3 emoji buttons appear fixed bottom-right.
2. The current theme button (🌙 by default) is visually active (full opacity, background highlight).
3. Click ☀️ → page switches to blanc theme instantly, ☀️ becomes active.
4. Reload the page → blanc theme loads with no flash (FOUC prevention working).
5. Click 🎮 → page switches to old-steam (dark greenish-brown, gold accents).
6. Reload → old-steam persists.
7. Click 🌙 → returns to dark theme, reload → dark theme loads (no `data-theme` attribute on `<html>`).
8. Navigate to `/admin/ccl` → theme switcher present and correct theme active.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/templates/fragments/nav.html
git commit -m "feat: add floating theme switcher widget to nav fragment"
```
