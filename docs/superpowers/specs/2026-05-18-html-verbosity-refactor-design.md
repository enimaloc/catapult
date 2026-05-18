# HTML Verbosity Refactor — Design Spec

**Date:** 2026-05-18  
**Scope:** All 15 Thymeleaf templates in `src/main/resources/templates/`

---

## Problem

Three distinct verbosity sources were identified:

1. **Inline styles** — `obs-setup.html` and `global-process-rules.html` use `style="..."` attributes extensively (padding, flex layout, font weight, colours) instead of CSS classes.
2. **Duplicated predicate fragment** — The predicates display table and add-predicate form are near-identical in `obs-setup.html` and `global-process-rules.html`. Only the action URLs differ.
3. **Duplicated game search JS** — `obsSearch()` in `obs-setup.html` and `adminSearch()` in `global-process-rules.html` reimplement `gameSearch()` already in `app.js`, ignoring the `data-*` attribute convention already used by `members.html`.
4. **Inline `<style>` block** — `igdb-explorer.html` has 130 lines of CSS inline; `.btn-run` is already defined in `app.css` (line 862).

---

## Architecture

Four independent layers, each delivered as a separate commit. Each layer is safe to revert independently.

### Layer 1 — Utility CSS classes (`app.css` + all templates)

Add utility classes to `app.css` after the existing utilities section (`.text-muted`, etc.):

| Class | CSS |
|---|---|
| `.flex-row` | `display:flex; gap:8px; align-items:center` |
| `.flex-col` | `display:flex; flex-direction:column; gap:2px` |
| `.flex-end-wrap` | `display:flex; gap:8px; align-items:flex-end; flex-wrap:wrap` |
| `.label-muted` | `font-size:.85em; font-weight:600; color:var(--text-dim)` |
| `.p-8` | `padding:8px` |
| `.p-4` | `padding:4px` |
| `.mb-8` | `margin-bottom:8px` |
| `.mb-12` | `margin-bottom:12px` |
| `.mb-16` | `margin-bottom:16px` |

Replace all matching `style="..."` attributes across all templates with these classes. The `.table` class already exists in `app.css` and already sets `width:100%; border-collapse:collapse` — inline duplicates are removed.

**Commit:** `refactor: extract inline styles to utility CSS classes`

---

### Layer 2 — Shared predicate fragment

New file: `src/main/resources/templates/fragments/predicates.html`

Two parameterised sub-fragments:

**`predicate-table(predicates, deleteBaseUrl)`**  
Renders the read-only predicates table. Because Thymeleaf fragment parameters cannot be Thymeleaf URL expressions, `deleteBaseUrl` is passed as a pre-built string via `th:with` at the call site (e.g. `th:with="deleteUrl=@{/obs/process-bindings/{id}/predicates/{predId}/delete(id=${pb.id})}"`) and the `predId` path variable is appended inside the fragment using string concatenation.

**`predicate-form(connectors, predicateTypes, osTargets, addUrl)`**  
Renders the add-predicate form. `addUrl` is a pre-built string passed via `th:with` at the call site.

Callers replace their inline blocks with:
```html
<div th:with="deleteUrl=@{/obs/process-bindings/{id}/predicates(id=${pb.id})}"
     th:replace="~{fragments/predicates::predicate-table(${pb.predicates}, ${deleteUrl})}"></div>
<div th:with="addUrl=@{/obs/process-bindings/{id}/predicates(id=${pb.id})}"
     th:replace="~{fragments/predicates::predicate-form(${connectors}, ${predicateTypes}, ${osTargets}, ${addUrl})}"></div>
```

**Commit:** `refactor: extract predicate table and form into shared fragment`

---

### Layer 3 — Remove duplicate game search JS

`obs-setup.html` and `global-process-rules.html` each have a self-invoking `<script>` block (~40 lines) implementing a game search function. These are deleted entirely.

The search inputs are migrated to use the `data-*` convention already established in `members.html` and supported by `gameSearch()` in `app.js`:

```html
<input type="text"
       oninput="gameSearch(event)"
       data-results-id="gameResults-obs"
       data-gameid-field="twitchGameId-obs"
       data-gamename-field="twitchGameName-obs">
```

No changes to `app.js` required. The existing `data-search-url` attribute allows overriding the search endpoint if needed.

**Commit:** `refactor: replace duplicate game search JS with shared gameSearch()`

---

### Layer 4 — Migrate `igdb-explorer.html` inline styles to `app.css`

`igdb-explorer.html` has a `<style>` block (~130 lines) with classes specific to the IGDB Explorer page. The block is removed from the HTML file and its contents are appended to `app.css` under a `/* IGDB Explorer */` section comment.

The duplicate `.btn-run` definition inside the `<style>` block is dropped — the existing definition in `app.css` (line 862) is kept.

No HTML attribute changes required; class names are unchanged.

**Commit:** `refactor: move igdb-explorer inline styles to app.css`

---

## Files Changed

| File | Layer(s) |
|---|---|
| `static/css/app.css` | 1, 4 |
| `fragments/obs-setup.html` | 1, 2, 3 |
| `admin/global-process-rules.html` | 1, 2, 3 |
| `dev/igdb-explorer.html` | 1, 4 |
| `admin/members.html` | 1 |
| `admin/ccl.html` | 1 |
| `app.html` | 1 |
| `fragments/bindings.html` | 1 |
| `fragments/connections.html` | 1 |
| `fragments/danger-zone.html` | 1 |
| `fragments/no-game-settings.html` | 1 |
| `fragments/status.html` | 1 |
| `login.html` | 1 |
| `template.html` | 1 |
| `fragments/predicates.html` *(new)* | 2 |

---

## Out of Scope

- CSRF token handling (existing `<!-- nosemgrep -->` pattern is kept as-is)
- `app.js` logic changes
- Any new features or behaviour changes
