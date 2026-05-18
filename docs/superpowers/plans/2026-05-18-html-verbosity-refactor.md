# HTML Verbosity Refactor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove inline styles, duplicate JS, and duplicated HTML from 15 Thymeleaf templates by applying existing CSS classes, creating a shared predicate fragment, consolidating game-search JS, and migrating inline `<style>` blocks to `app.css`.

**Architecture:** Four independent layers (CSS application → shared fragment → JS deduplication → style block migration), each committed separately so any layer can be reverted without affecting the others. `app.css` already contains most needed classes — the main work is wiring templates to them.

**Tech Stack:** Thymeleaf, Spring Boot (Gradle / `./gradlew`), vanilla JS, CSS custom properties

---

## File Map

| File | Layer(s) | Action |
|---|---|---|
| `static/css/app.css` | 1, 4 | Add 8 utility classes; append IGDB Explorer section |
| `static/js/app.js` | 3 | Add box-art rendering to `gameSearch()` |
| `templates/fragments/obs-setup.html` | 1, 2, 3 | Apply classes; replace predicate block with fragment calls; remove inline JS |
| `templates/admin/global-process-rules.html` | 1, 2, 3 | Apply classes; replace predicate block with fragment calls; remove inline JS |
| `templates/fragments/predicates.html` *(new)* | 2 | Two parameterised sub-fragments |
| `templates/dev/igdb-explorer.html` | 1, 4 | Apply classes; remove `<style>` block |
| `templates/app.html` | 1, 4 | Remove `<style>` block (already in `app.css`); apply classes |
| `templates/admin/members.html` | 1 | Apply classes |
| `templates/admin/ccl.html` | 1 | Apply classes |
| `templates/template.html` | 1 | Apply classes |
| `templates/fragments/bindings.html` | 1 | Apply classes |
| `templates/fragments/connections.html` | 1 | Apply classes |
| `templates/fragments/danger-zone.html` | 1 | Apply classes |
| `templates/fragments/no-game-settings.html` | 1 | Apply classes |
| `templates/fragments/status.html` | 1 | Apply classes |

---

## Layer 1 — Apply CSS classes (inline styles → existing/new classes)

### Task 1: Add 8 utility classes to `app.css`

**Files:**
- Modify: `src/main/resources/static/css/app.css` (after line 669, in the `/* Utilities */` section)

- [ ] **Step 1: Append to the Utilities section in `app.css`**

Open `src/main/resources/static/css/app.css`. After line 669 (`.text-sm    { font-size: 12px; }`), insert:

```css
.mb-8           { margin-bottom: 8px; }
.mb-12          { margin-bottom: 12px; }
.mb-16          { margin-bottom: 16px; }
.m-0            { margin: 0; }
.align-self-end { align-self: end; }
.label-muted    { font-size: .85em; font-weight: 600; color: var(--text-dim); }
```

Also add two classes needed by `bindings.html` / `app.html` (currently only in `app.html`'s inline `<style>`). Insert in the `/* Inline edit row */` section after line 708:

```css
.inline-edit-field       { display: flex; flex-direction: column; gap: 4px; }
.inline-edit-field label { font-size: .8em; font-weight: 600; }
.ccl-checkboxes          { display: flex; flex-wrap: wrap; gap: 8px; }
```

- [ ] **Step 2: Verify app.css is valid (no syntax error)**

```bash
grep -c '{' src/main/resources/static/css/app.css && grep -c '}' src/main/resources/static/css/app.css
```
Expected: both counts equal (balanced braces).

---

### Task 2: Apply classes to `obs-setup.html`

**Key insight:** `app.css` already defines `.api-key-display`, `.api-key-code`, `.code-block`, `.process-table`, `.predicate-table`, `.predicate-form`, `.predicate-form-field`, `.form-field`, `.form-row`, `.alert-error`, and `details summary { cursor:pointer; font-weight:600 }`. The template just isn't using them.

**Files:**
- Modify: `src/main/resources/templates/fragments/obs-setup.html`

- [ ] **Step 1: Replace the API key display section (lines 15–27)**

Before:
```html
<div th:unless="${apiKey == null}">
    <div style="margin-bottom:12px">
        <label style="font-size:.85em;font-weight:600;color:#555" th:text="#{obs.apikey.label}">Clé API</label>
        <div style="display:flex;gap:8px;align-items:center;margin-top:4px">
            <code th:text="${apiKey}" style="background:#f4f4f4;padding:6px 10px;border-radius:4px;font-size:.9em;flex:1;word-break:break-all"></code>
            <form th:action="@{/obs/generate-key}" method="post" style="margin:0">
                <button type="submit" class="btn btn-sm btn-outline" th:text="#{obs.apikey.regenerate}">↺ Régénérer</button>
            </form>
            <form th:action="@{/obs/revoke-key}" method="post" style="margin:0">
                <button type="submit" class="btn btn-sm btn-danger" th:text="#{obs.apikey.revoke}">Révoquer</button>
            </form>
        </div>
    </div>
```

After:
```html
<div th:unless="${apiKey == null}">
    <div class="mb-12">
        <label class="label-muted" th:text="#{obs.apikey.label}">Clé API</label>
        <div class="api-key-display">
            <code th:text="${apiKey}" class="api-key-code"></code>
            <form th:action="@{/obs/generate-key}" method="post" class="m-0">
                <button type="submit" class="btn btn-sm btn-outline" th:text="#{obs.apikey.regenerate}">↺ Régénérer</button>
            </form>
            <form th:action="@{/obs/revoke-key}" method="post" class="m-0">
                <button type="submit" class="btn btn-sm btn-danger" th:text="#{obs.apikey.revoke}">Révoquer</button>
            </form>
        </div>
    </div>
```

- [ ] **Step 2: Replace the installation guide `<details>` (lines 30–41)**

Before:
```html
<details style="margin-bottom:16px">
    <summary style="cursor:pointer;font-weight:600" th:text="#{obs.guide.title}">📋 Guide d'installation</summary>
    <ol style="margin-top:12px;line-height:2">
```

After:
```html
<details class="mb-16">
    <summary th:text="#{obs.guide.title}">📋 Guide d'installation</summary>
    <ol style="margin-top:12px;line-height:2">
```
(The `cursor:pointer;font-weight:600` on `<summary>` is handled globally by `details summary { ... }` in `app.css`.)

- [ ] **Step 3: Replace the OBS script `<details>` block (lines 44–53)**

Before:
```html
<details style="margin-bottom:16px">
    <summary style="cursor:pointer;font-weight:600" th:text="#{obs.script.title}">📄 Script OBS Python</summary>
    <div style="position:relative;margin-top:8px">
        <button type="button"
                onclick="navigator.clipboard.writeText(document.getElementById('obs-script').textContent)"
                style="position:absolute;top:6px;right:6px;font-size:.75em"
                class="btn btn-sm btn-outline" th:text="#{obs.script.copy}">Copier</button>
        <pre id="obs-script" style="background:#1e1e1e;color:#d4d4d4;padding:16px;border-radius:6px;overflow-x:auto;font-size:.82em;line-height:1.5"><code th:text="${obsScript}" style="white-space:pre;display:block"></code></pre>
    </div>
</details>
```

After:
```html
<details class="mb-16">
    <summary th:text="#{obs.script.title}">📄 Script OBS Python</summary>
    <div style="position:relative;margin-top:8px">
        <button type="button"
                onclick="navigator.clipboard.writeText(document.getElementById('obs-script').textContent)"
                style="position:absolute;top:6px;right:6px;font-size:.75em"
                class="btn btn-sm btn-outline" th:text="#{obs.script.copy}">Copier</button>
        <pre id="obs-script" class="code-block"><code th:text="${obsScript}"></code></pre>
    </div>
</details>
```

- [ ] **Step 4: Replace the process bindings table (lines 56–166)**

Replace the outer table and all `style="..."` on its `<th>`, `<td>`, and `<tr>` elements:

```html
<h4 class="mb-8" th:text="#{obs.bindings.title}">Associations processus → jeu</h4>

<div th:if="${processBindings.empty}" class="text-muted mb-12" th:text="#{obs.bindings.empty}">
    Aucune association configurée.
</div>

<div th:unless="${processBindings.empty}" class="mb-12">
    <table class="process-table">
        <thead>
            <tr>
                <th th:text="#{obs.bindings.column.process}">Processus</th>
                <th th:text="#{obs.bindings.column.game}">Jeu Twitch</th>
                <th th:text="#{obs.bindings.column.predicates}">Prédicats</th>
                <th></th>
            </tr>
        </thead>
        <tbody>
            <tr th:each="pb : ${processBindings}">
                <td><code th:text="${pb.processName}"></code></td>
                <td th:text="${pb.twitchGameName}"></td>

                <td style="min-width:260px">
                    <details>
                        <summary style="font-size:.85em">
                            <span th:text="#{obs.predicate.count(${pb.predicates.size()})}">Aucun prédicat</span>
                        </summary>

                        <div th:unless="${pb.predicates.empty}" class="mb-8">
                            <table class="predicate-table">
                                <thead>
                                    <tr>
                                        <th th:text="#{obs.predicate.column.link}">Lien</th>
                                        <th th:text="#{obs.predicate.column.type}">Type</th>
                                        <th th:text="#{obs.predicate.column.key}">Clé</th>
                                        <th th:text="#{obs.predicate.column.value}">Valeur</th>
                                        <th th:text="#{obs.predicate.column.os}">OS</th>
                                        <th></th>
                                    </tr>
                                </thead>
                                <tbody>
                                    <tr th:each="pred, iterStat : ${pb.predicates}">
                                        <td class="text-muted">
                                            <span th:if="${iterStat.first}">—</span>
                                            <span th:unless="${iterStat.first}" th:text="${pred.connector}"></span>
                                        </td>
                                        <td th:text="${pred.type}"></td>
                                        <td><code th:text="${pred.key}"></code></td>
                                        <td><code th:text="${pred.value}"></code></td>
                                        <td th:text="${pred.osTarget}"></td>
                                        <td>
                                            <form th:action="@{/obs/process-bindings/{id}/predicates/{predId}/delete(id=${pb.id},predId=${pred.id})}" method="post">
                                                <button type="submit" class="btn btn-sm btn-danger" style="padding:2px 6px;font-size:.75em">✕</button>
                                            </form>
                                        </td>
                                    </tr>
                                </tbody>
                            </table>
                        </div>

                        <form th:action="@{/obs/process-bindings/{id}/predicates(id=${pb.id})}" method="post"
                              class="predicate-form">
                            <div class="predicate-form-field">
                                <label th:text="#{obs.predicate.form.link}">Lien</label>
                                <select name="connector">
                                    <option th:each="c : ${connectors}" th:value="${c}" th:text="${c}"></option>
                                </select>
                            </div>
                            <div class="predicate-form-field">
                                <label th:text="#{obs.predicate.form.type}">Type</label>
                                <select name="type">
                                    <option th:each="t : ${predicateTypes}" th:value="${t}" th:text="${t}"></option>
                                </select>
                            </div>
                            <div class="predicate-form-field">
                                <label th:text="#{obs.predicate.form.value_label}">Value</label>
                                <input type="text" name="value" required th:placeholder="#{obs.predicate.form.value_placeholder}">
                            </div>
                            <div class="predicate-form-field">
                                <label th:text="#{obs.predicate.form.key_label}">Key</label>
                                <input type="text" name="key" th:placeholder="#{obs.predicate.form.key_placeholder}" style="width:100px">
                            </div>
                            <div class="predicate-form-field">
                                <label th:text="#{obs.predicate.form.os}">OS</label>
                                <select name="osTarget">
                                    <option th:each="o : ${osTargets}" th:value="${o}" th:text="${o}"></option>
                                </select>
                            </div>
                            <button type="submit" class="btn btn-sm btn-primary align-self-end" th:text="'+ ' + #{common.add}">+ Ajouter</button>
                        </form>
                    </details>
                </td>

                <td style="text-align:right">
                    <form th:action="@{/obs/process-bindings/{id}/delete(id=${pb.id})}" method="post">
                        <button type="submit" class="btn btn-sm btn-danger" th:text="#{common.delete}">Supprimer</button>
                    </form>
                </td>
            </tr>
        </tbody>
    </table>
</div>
```

- [ ] **Step 5: Replace the add-binding form (lines 170–188)**

Before:
```html
<form th:action="@{/obs/process-bindings}" method="post" style="display:flex;gap:8px;align-items:flex-end;flex-wrap:wrap">
    <div style="display:flex;flex-direction:column;gap:4px">
        <label style="font-size:.8em;font-weight:600;color:#555" th:text="#{obs.binding.add.process_label}">...</label>
        <input type="text" name="processName" ... style="padding:6px 10px;border:1px solid #ccc;border-radius:4px;font-size:.9em">
    </div>
    <div style="display:flex;flex-direction:column;gap:4px;position:relative">
        <label style="font-size:.8em;font-weight:600;color:#555" th:text="#{obs.binding.add.game_label}">...</label>
        ...
    </div>
```

After:
```html
<form th:action="@{/obs/process-bindings}" method="post" class="form-row">
    <div class="form-field">
        <label th:text="#{obs.binding.add.process_label}">Nom du processus</label>
        <input type="text" name="processName" th:placeholder="#{obs.binding.add.process_placeholder}" required
               style="padding:6px 10px;border:1px solid #ccc;border-radius:4px;font-size:.9em">
    </div>
    <div class="form-field" style="position:relative">
        <label th:text="#{obs.binding.add.game_label}">Jeu Twitch</label>
        ...
    </div>
```
(`position:relative` stays inline on this one div — `.search-wrapper` handles the dropdown positioning but the outer field also needs it. `.form-field` already gives flex-column layout.)

---

### Task 3: Apply classes to `global-process-rules.html`

**Files:**
- Modify: `src/main/resources/templates/admin/global-process-rules.html`

- [ ] **Step 1: Replace error div (lines 14–16)**

Before:
```html
<div th:if="${error}" style="background:#fee;border:1px solid #fcc;padding:10px 14px;border-radius:4px;margin-bottom:1rem;color:#c00"
     th:text="${error}"></div>
```

After:
```html
<div th:if="${error}" class="alert-error" th:text="${error}"></div>
```

- [ ] **Step 2: Replace the global rules table (lines 22–120)**

Replace the outer table and its inline-styled children with:

```html
<div th:unless="${globalRules.empty}" class="mb-16">
    <table class="process-table">
        <thead>
            <tr>
                <th th:text="#{admin.process_rules.column.pattern}">Pattern processus</th>
                <th th:text="#{admin.process_rules.column.regex}">Regex</th>
                <th th:text="#{admin.process_rules.column.game}">Jeu Twitch</th>
                <th th:text="#{admin.process_rules.column.predicates}">Prédicats</th>
                <th></th>
            </tr>
        </thead>
        <tbody>
            <tr th:each="rule : ${globalRules}">
                <td><code th:text="${rule.processName}"></code></td>
                <td>
                    <span th:if="${rule.regex}" class="badge badge-regex">regex</span>
                    <span th:unless="${rule.regex}" class="text-muted text-sm">exact</span>
                </td>
                <td th:text="${rule.twitchGameName}"></td>

                <td style="min-width:320px">
                    <div th:unless="${rule.predicates.empty}" class="mb-8">
                        <table class="predicate-table">
                            <thead>
                                <tr>
                                    <th th:text="#{obs.predicate.column.link}">Lien</th>
                                    <th th:text="#{obs.predicate.column.type}">Type</th>
                                    <th th:text="#{obs.predicate.column.key}">Clé</th>
                                    <th th:text="#{obs.predicate.column.value}">Valeur</th>
                                    <th th:text="#{obs.predicate.column.os}">OS</th>
                                    <th></th>
                                </tr>
                            </thead>
                            <tbody>
                                <tr th:each="pred, iterStat : ${rule.predicates}">
                                    <td class="text-muted">
                                        <span th:if="${iterStat.first}">—</span>
                                        <span th:unless="${iterStat.first}" th:text="${pred.connector}"></span>
                                    </td>
                                    <td th:text="${pred.type}"></td>
                                    <td><code th:text="${pred.key}"></code></td>
                                    <td><code th:text="${pred.value}"></code></td>
                                    <td th:text="${pred.osTarget}"></td>
                                    <td>
                                        <form th:action="@{/admin/process-rules/{id}/predicates/{predId}/delete(id=${rule.id},predId=${pred.id})}" method="post">
                                            <button type="submit" class="btn btn-sm btn-danger" style="padding:2px 6px;font-size:.75em">✕</button>
                                        </form>
                                    </td>
                                </tr>
                            </tbody>
                        </table>
                    </div>

                    <form th:action="@{/admin/process-rules/{id}/predicates(id=${rule.id})}" method="post"
                          class="predicate-form">
                        <div class="predicate-form-field">
                            <label th:text="#{obs.predicate.form.link}">Lien</label>
                            <select name="connector">
                                <option th:each="c : ${connectors}" th:value="${c}" th:text="${c}"></option>
                            </select>
                        </div>
                        <div class="predicate-form-field">
                            <label th:text="#{obs.predicate.form.type}">Type</label>
                            <select name="type">
                                <option th:each="t : ${predicateTypes}" th:value="${t}" th:text="${t}"></option>
                            </select>
                        </div>
                        <div class="predicate-form-field">
                            <label th:text="#{obs.predicate.form.value_label}">Valeur</label>
                            <input type="text" name="value" required th:placeholder="#{obs.predicate.form.value_placeholder}">
                        </div>
                        <div class="predicate-form-field">
                            <label th:text="#{obs.predicate.form.key_label}">Clé</label>
                            <input type="text" name="key" th:placeholder="#{obs.predicate.form.key_placeholder}" style="width:90px">
                        </div>
                        <div class="predicate-form-field">
                            <label th:text="#{obs.predicate.form.os}">OS</label>
                            <select name="osTarget">
                                <option th:each="o : ${osTargets}" th:value="${o}" th:text="${o}"></option>
                            </select>
                        </div>
                        <button type="submit" class="btn btn-sm btn-primary align-self-end" th:text="'+ ' + #{common.add}">+ Ajouter</button>
                    </form>
                </td>

                <td style="text-align:right">
                    <form th:action="@{/admin/process-rules/{id}/delete(id=${rule.id})}" method="post">
                        <button type="submit" class="btn btn-sm btn-danger" th:text="#{common.delete}">Supprimer</button>
                    </form>
                </td>
            </tr>
        </tbody>
    </table>
</div>
```

Note: explicit `<input type="hidden" th:name="${_csrf.parameterName}" .../>` tokens and their `<!-- nosemgrep -->` comments are removed — Thymeleaf's `th:action` provides CSRF automatically.

- [ ] **Step 3: Replace the add-rule card form (lines 122–155)**

Before:
```html
<div class="card" style="padding:1.5rem">
    <h4 style="margin-top:0;margin-bottom:1rem" th:text="#{admin.process_rules.add.title}">...</h4>
    <p class="text-muted" style="font-size:.85em;margin-bottom:1rem" th:utext="#{admin.process_rules.add.note}">...</p>
    <form th:action="@{/admin/process-rules}" method="post"
          style="display:flex;gap:12px;align-items:flex-end;flex-wrap:wrap">
        ...
        <div style="display:flex;flex-direction:column;gap:4px">
            <label style="font-size:.8em;font-weight:600;color:#555" ...>...</label>
            <input type="text" name="processName" ... style="padding:6px 10px;border:1px solid #ccc;border-radius:4px;font-size:.9em;width:220px">
        </div>
```

After:
```html
<div class="card" style="padding:1.5rem">
    <h4 style="margin-top:0" class="mb-12" th:text="#{admin.process_rules.add.title}">...</h4>
    <p class="text-muted text-sm mb-12" th:utext="#{admin.process_rules.add.note}">...</p>
    <form th:action="@{/admin/process-rules}" method="post" class="form-row">
        ...
        <div class="form-field">
            <label th:text="#{admin.process_rules.add.process_label}">Pattern processus</label>
            <input type="text" name="processName" th:placeholder="#{admin.process_rules.add.process_placeholder}" required
                   style="padding:6px 10px;border:1px solid #ccc;border-radius:4px;font-size:.9em;width:220px">
        </div>
```
(Remove the explicit CSRF `<input>` from this form too — `th:action` handles it.)

---

### Task 4: Apply classes to remaining templates

**Files:**
- `templates/admin/ccl.html`
- `templates/admin/members.html`
- `templates/template.html`

- [ ] **Step 1: `ccl.html` — header row (line 8)**

Before:
```html
<div style="display:flex; align-items:center; justify-content:space-between; margin-bottom:1rem;">
```
After:
```html
<div style="display:flex; align-items:center; justify-content:space-between" class="mb-12">
```
(`justify-content:space-between` has no existing utility — keep that one inline.)

- [ ] **Step 2: `ccl.html` — select wrapper (line 45)**

Before:
```html
<div style="margin-top:0.5rem;">
```
After:
```html
<div style="margin-top:0.5rem">
```
(Minimal; this is a one-off. No class needed — it's not repeated.)

- [ ] **Step 3: `members.html` — steam set/clear row (line 73)**

Before:
```html
<div th:if="${member.steamId != null}" style="display:flex;gap:6px;align-items:flex-end">
```
After:
```html
<div th:if="${member.steamId != null}" style="display:flex;gap:6px;align-items:flex-end">
```
(No matching utility — `align-items:flex-end` differs from `.form-row`. Keep inline.)

- [ ] **Step 4: `app.html` — loading placeholders (lines 31, 34)**

Before:
```html
<div class="card"><p class="text-muted" style="padding:16px" th:text="#{app.loading_obs}">Chargement OBS…</p></div>
<div class="card"><p class="text-muted" style="padding:16px" th:text="#{app.loading}">Chargement…</p></div>
```
After:
```html
<div class="card"><p class="text-muted" style="padding:1rem" th:text="#{app.loading_obs}">Chargement OBS…</p></div>
<div class="card"><p class="text-muted" style="padding:1rem" th:text="#{app.loading}">Chargement…</p></div>
```
(No change to padding value — just confirming these are one-offs. Keep as-is.)

- [ ] **Step 5: `template.html` — impersonate banner (line 30)**

Before:
```html
<div th:if="${user != null}" sec:authorize="hasAuthority('ROLE_PREVIOUS_ADMINISTRATOR')"
     style="background:var(--color-warning,#f59e0b);color:#000;padding:0.5rem 1rem;display:flex;align-items:center;gap:1rem;font-size:0.9rem;">
```
After: leave this as-is — it's a single-use banner with no matching class and the style is intentional.

---

### Task 5: Apply classes to `no-game-settings.html` and commit Layer 1

**Files:**
- Modify: `src/main/resources/templates/fragments/no-game-settings.html`

Note: `connections.html`, `danger-zone.html`, `status.html`, `bot.html`, `activity-log.html` have no inline styles — no changes needed.

- [ ] **Step 1: Replace two inline styles in `no-game-settings.html`**

Line 6 — before:
```html
<p class="text-muted" style="margin-bottom:1rem" th:text="#{no_game.subtitle}">
```
After:
```html
<p class="text-muted mb-12" th:text="#{no_game.subtitle}">
```

Line 43 — before:
```html
<div class="inline-edit-actions" style="margin-top:12px">
```
After:
```html
<div class="inline-edit-actions">
```
(`.inline-edit-actions` already sets `margin-top: 12px` in `app.css` — the inline style is redundant.)

- [ ] **Step 2: Stage and commit**

```bash
git add src/main/resources/static/css/app.css \
        src/main/resources/templates/fragments/obs-setup.html \
        src/main/resources/templates/fragments/no-game-settings.html \
        src/main/resources/templates/admin/global-process-rules.html \
        src/main/resources/templates/admin/ccl.html \
        src/main/resources/templates/admin/members.html \
        src/main/resources/templates/app.html \
        src/main/resources/templates/template.html
git commit -m "refactor: extract inline styles to utility CSS classes"
```

---

## Layer 2 — Shared predicate fragment

### Task 6: Create `fragments/predicates.html`

**Files:**
- Create: `src/main/resources/templates/fragments/predicates.html`

- [ ] **Step 1: Create the file with two parameterised fragments**

```html
<!DOCTYPE html>
<html lang="fr" xmlns:th="http://www.thymeleaf.org">
<body>

<!--
  predicate-table: renders the read-only predicates table.
  Parameters:
    predicates  — list of predicate objects (connector, type, key, value, osTarget, id)
    urlPrefix   — URL path prefix, e.g. '/obs/process-bindings' or '/admin/process-rules'
    entityId    — the binding/rule ID (Long)
-->
<th:block th:fragment="predicate-table(predicates, urlPrefix, entityId)">
    <div th:unless="${predicates.empty}" class="mb-8">
        <table class="predicate-table">
            <thead>
                <tr>
                    <th th:text="#{obs.predicate.column.link}">Lien</th>
                    <th th:text="#{obs.predicate.column.type}">Type</th>
                    <th th:text="#{obs.predicate.column.key}">Clé</th>
                    <th th:text="#{obs.predicate.column.value}">Valeur</th>
                    <th th:text="#{obs.predicate.column.os}">OS</th>
                    <th></th>
                </tr>
            </thead>
            <tbody>
                <tr th:each="pred, iterStat : ${predicates}">
                    <td class="text-muted">
                        <span th:if="${iterStat.first}">—</span>
                        <span th:unless="${iterStat.first}" th:text="${pred.connector}"></span>
                    </td>
                    <td th:text="${pred.type}"></td>
                    <td><code th:text="${pred.key}"></code></td>
                    <td><code th:text="${pred.value}"></code></td>
                    <td th:text="${pred.osTarget}"></td>
                    <td>
                        <form th:action="@{__${urlPrefix}__/{id}/predicates/{predId}/delete(id=${entityId},predId=${pred.id})}" method="post">
                            <button type="submit" class="btn btn-sm btn-danger" style="padding:2px 6px;font-size:.75em">✕</button>
                        </form>
                    </td>
                </tr>
            </tbody>
        </table>
    </div>
</th:block>

<!--
  predicate-form: renders the add-predicate inline form.
  Parameters:
    connectors      — list of connector enum values
    predicateTypes  — list of predicate type enum values
    osTargets       — list of OS target enum values
    urlPrefix       — URL path prefix (same as above)
    entityId        — the binding/rule ID (Long)
-->
<form th:fragment="predicate-form(connectors, predicateTypes, osTargets, urlPrefix, entityId)"
      th:action="@{__${urlPrefix}__/{id}/predicates(id=${entityId})}" method="post"
      class="predicate-form">
    <div class="predicate-form-field">
        <label th:text="#{obs.predicate.form.link}">Lien</label>
        <select name="connector">
            <option th:each="c : ${connectors}" th:value="${c}" th:text="${c}"></option>
        </select>
    </div>
    <div class="predicate-form-field">
        <label th:text="#{obs.predicate.form.type}">Type</label>
        <select name="type">
            <option th:each="t : ${predicateTypes}" th:value="${t}" th:text="${t}"></option>
        </select>
    </div>
    <div class="predicate-form-field">
        <label th:text="#{obs.predicate.form.value_label}">Value</label>
        <input type="text" name="value" required th:placeholder="#{obs.predicate.form.value_placeholder}">
    </div>
    <div class="predicate-form-field">
        <label th:text="#{obs.predicate.form.key_label}">Key</label>
        <input type="text" name="key" th:placeholder="#{obs.predicate.form.key_placeholder}" style="width:100px">
    </div>
    <div class="predicate-form-field">
        <label th:text="#{obs.predicate.form.os}">OS</label>
        <select name="osTarget">
            <option th:each="o : ${osTargets}" th:value="${o}" th:text="${o}"></option>
        </select>
    </div>
    <button type="submit" class="btn btn-sm btn-primary align-self-end" th:text="'+ ' + #{common.add}">+ Ajouter</button>
</form>

</body>
</html>
```

**Key:** `@{__${urlPrefix}__/{id}/predicates/...}` uses Thymeleaf preprocessing (`__${...}__`) to substitute the runtime string value of `urlPrefix` into the URL expression before URL processing occurs.

---

### Task 7: Replace predicate block in `obs-setup.html` with fragment calls

**Files:**
- Modify: `src/main/resources/templates/fragments/obs-setup.html`

- [ ] **Step 1: Replace the predicates `<td>` content**

Find the `<td style="padding:8px;min-width:260px">` cell (after Task 2, it's `<td style="min-width:260px">`). Replace its `<details>` inner content:

Before (after Task 2 changes):
```html
<td style="min-width:260px">
    <details>
        <summary style="font-size:.85em">
            <span th:text="#{obs.predicate.count(${pb.predicates.size()})}">Aucun prédicat</span>
        </summary>

        <div th:unless="${pb.predicates.empty}" class="mb-8">
            <table class="predicate-table">
                ... (predicate table inline) ...
            </table>
        </div>

        <form ... class="predicate-form">
            ... (predicate form inline) ...
        </form>
    </details>
</td>
```

After:
```html
<td style="min-width:260px">
    <details>
        <summary style="font-size:.85em">
            <span th:text="#{obs.predicate.count(${pb.predicates.size()})}">Aucun prédicat</span>
        </summary>
        <div th:replace="~{fragments/predicates::predicate-table(${pb.predicates}, '/obs/process-bindings', ${pb.id})}"></div>
        <th:block th:replace="~{fragments/predicates::predicate-form(${connectors}, ${predicateTypes}, ${osTargets}, '/obs/process-bindings', ${pb.id})}"></th:block>
    </details>
</td>
```

---

### Task 8: Replace predicate block in `global-process-rules.html` with fragment calls

**Files:**
- Modify: `src/main/resources/templates/admin/global-process-rules.html`

- [ ] **Step 1: Replace the predicates `<td>` content**

Find `<td style="min-width:320px">`. Replace its inline predicate table and form:

Before (after Task 3 changes):
```html
<td style="min-width:320px">
    <div th:unless="${rule.predicates.empty}" class="mb-8">
        <table class="predicate-table">
            ... (predicate table inline) ...
        </table>
    </div>
    <form ... class="predicate-form">
        ... (predicate form inline) ...
    </form>
</td>
```

After:
```html
<td style="min-width:320px">
    <div th:replace="~{fragments/predicates::predicate-table(${rule.predicates}, '/admin/process-rules', ${rule.id})}"></div>
    <th:block th:replace="~{fragments/predicates::predicate-form(${connectors}, ${predicateTypes}, ${osTargets}, '/admin/process-rules', ${rule.id})}"></th:block>
</td>
```

---

### Task 9: Commit Layer 2

- [ ] **Step 1: Stage and commit**

```bash
git add src/main/resources/templates/fragments/predicates.html \
        src/main/resources/templates/fragments/obs-setup.html \
        src/main/resources/templates/admin/global-process-rules.html
git commit -m "refactor: extract predicate table and form into shared fragment"
```

---

## Layer 3 — Remove duplicate game search JS

### Task 10: Add box-art rendering to `gameSearch()` in `app.js`

The inline `obsSearch` and `adminSearch` functions both render box art thumbnails; `gameSearch` in `app.js` does not. Update `gameSearch` first so the migration is feature-equivalent.

**Files:**
- Modify: `src/main/resources/static/js/app.js`

- [ ] **Step 1: Replace the `forEach` block inside `gameSearch`**

Before (lines 20–30):
```js
data.forEach(game => {
    const li = document.createElement('li');
    li.textContent = game.name;
    li.addEventListener('click', () => {
        document.getElementById(gameIdId).value = game.id;
        document.getElementById(gameNameId).value = game.name;
        input.value = game.name;
        results.style.display = 'none';
    });
    results.appendChild(li);
});
```

After:
```js
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
        document.getElementById(gameIdId).value = game.id;
        document.getElementById(gameNameId).value = game.name;
        input.value = game.name;
        results.style.display = 'none';
    });
    results.appendChild(li);
});
```

---

### Task 11: Replace `obsSearch` in `obs-setup.html` with `gameSearch`

**Files:**
- Modify: `src/main/resources/templates/fragments/obs-setup.html`

- [ ] **Step 1: Update the game search input to use `data-*` attributes**

Find the `<input type="text" id="obsGameSearch" ...>` in the add-binding form. Replace:

Before:
```html
<input type="text" id="obsGameSearch" th:placeholder="#{common.search_placeholder}"
       oninput="obsSearch(event)" autocomplete="off"
       style="padding:6px 10px;border:1px solid #ccc;border-radius:4px;font-size:.9em;width:220px">
```

After:
```html
<input type="text" id="obsGameSearch" th:placeholder="#{common.search_placeholder}"
       oninput="gameSearch(event)"
       data-results-id="gameResults-obs"
       data-gameid-field="twitchGameId-obs"
       data-gamename-field="twitchGameName-obs"
       autocomplete="off"
       style="padding:6px 10px;border:1px solid #ccc;border-radius:4px;font-size:.9em;width:220px">
```

- [ ] **Step 2: Delete the entire inline `<script>` block (lines 190–229)**

Remove the entire `<script>` tag that defines `(function() { var _obsTimer = null; window.obsSearch = ... })();`.

---

### Task 12: Replace `adminSearch` in `global-process-rules.html` with `gameSearch`

**Files:**
- Modify: `src/main/resources/templates/admin/global-process-rules.html`

- [ ] **Step 1: Update the game search input**

Find `<input type="text" id="adminGameSearch" ...>`. Replace:

Before:
```html
<input type="text" id="adminGameSearch" th:placeholder="#{admin.process_rules.add.game_placeholder}"
       oninput="adminSearch(event)" autocomplete="off"
       style="padding:6px 10px;border:1px solid #ccc;border-radius:4px;font-size:.9em;width:220px">
```

After:
```html
<input type="text" id="adminGameSearch" th:placeholder="#{admin.process_rules.add.game_placeholder}"
       oninput="gameSearch(event)"
       data-results-id="gameResults-admin"
       data-gameid-field="twitchGameId-admin"
       data-gamename-field="twitchGameName-admin"
       autocomplete="off"
       style="padding:6px 10px;border:1px solid #ccc;border-radius:4px;font-size:.9em;width:220px">
```

- [ ] **Step 2: Delete the entire inline `<script>` block**

Remove the `<script>` tag that defines `(function() { var _timer = null; window.adminSearch = ... })();` (approximately lines 157–196).

---

### Task 13: Commit Layer 3

- [ ] **Step 1: Stage and commit**

```bash
git add src/main/resources/static/js/app.js \
        src/main/resources/templates/fragments/obs-setup.html \
        src/main/resources/templates/admin/global-process-rules.html
git commit -m "refactor: replace duplicate game search JS with shared gameSearch()"
```

---

## Layer 4 — Migrate inline `<style>` blocks to `app.css`

### Task 14: Migrate `igdb-explorer.html` styles → `app.css`

**Files:**
- Modify: `src/main/resources/templates/dev/igdb-explorer.html`
- Modify: `src/main/resources/static/css/app.css`

- [ ] **Step 1: Append IGDB Explorer section to `app.css`**

Add at the end of `app.css` (replacing the existing `.btn-run` section if present — check with `grep -n "btn-run" app.css` first):

```css
/* ============================================================
   IGDB Explorer (dev/igdb-explorer.html)
   ============================================================ */
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
.endpoint-row { display: flex; align-items: center; gap: 8px; }
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
.editor-actions { display: flex; align-items: center; gap: 10px; }
.htmx-indicator { color: #64748b; font-size: 12px; display: none; }
.htmx-request .htmx-indicator { display: inline; }
.results-panel { flex: 1; overflow: auto; padding: 16px; }
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
```

Note: do NOT add `.btn-run` — it already exists in `app.css` at an earlier line. Verify: `grep -n "btn-run" src/main/resources/static/css/app.css`.

- [ ] **Step 2: Remove the `<style>` block from `igdb-explorer.html`**

Delete lines 6–132 (the entire `<style>...</style>` block inside `<head>`). The `<head>` section becomes:

```html
<head>
    <title>Catapult — IGDB Explorer</title>
</head>
```

---

### Task 15: Migrate `app.html` inline `<style>` block → `app.css`

**Files:**
- Modify: `src/main/resources/templates/app.html`
- Modify: `src/main/resources/static/css/app.css` (add two missing classes)

- [ ] **Step 1: Add the two classes missing from `app.css`**

In `app.css`, in the `/* Inline edit row */` section (after line 708), add (already done in Task 1 — verify `.inline-edit-field` and `.ccl-checkboxes` are present):

```css
.inline-edit-field       { display: flex; flex-direction: column; gap: 4px; }
.inline-edit-field label { font-size: .8em; font-weight: 600; }
.ccl-checkboxes          { display: flex; flex-wrap: wrap; gap: 8px; }
```

If Task 1 was done, skip this step.

- [ ] **Step 2: Remove the `<style>` block from `app.html`**

Delete lines 6–26 (the `<style>...</style>` block). The `<head>` becomes:

```html
<head>
    <title>Catapult</title>
</head>
```

All classes defined in that block (`.toggle-label`, `.toggle-slider`, `.edit-row`, `.inline-edit-fields`, `.inline-edit-field`, `.inline-edit-actions`, `.ccl-checkboxes`) now come from `app.css`.

- [ ] **Step 3: Update `bindings.html` to use `.inline-edit-field` class**

In `src/main/resources/templates/fragments/bindings.html`, the inline-edit fields already use `class="inline-edit-field"` (line 87). Verify these render correctly.

---

### Task 16: Commit Layer 4

- [ ] **Step 1: Stage and commit**

```bash
git add src/main/resources/static/css/app.css \
        src/main/resources/templates/dev/igdb-explorer.html \
        src/main/resources/templates/app.html
git commit -m "refactor: move igdb-explorer and app inline styles to app.css"
```

---

## Out of Scope

- `app.html`'s `debouncedSearch` / `fetchSuggestions` inline JS — this is a third game-search variant used by `bindings.html` edit rows. Consolidating it requires changing the `data-*` attribute setup in `bindings.html` and the JS in `app.html`; it's a separate concern.
- `template.html` impersonate banner inline styles — single-use, intentional.
- CSRF token handling changes — the `<!-- nosemgrep -->` pattern on non-predicate forms in `members.html`, `admin/ccl.html` etc. is left as-is.
