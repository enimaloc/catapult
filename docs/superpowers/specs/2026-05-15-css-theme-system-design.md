# CSS Theme System — Design Spec

**Date:** 2026-05-15

## Overview

Add a client-side CSS theme system to Catapult with three themes: `dark` (default, unchanged), `blanc` (white/clean), and `old-steam` (Steam 2003 original palette). The active theme is persisted in `localStorage` and applied via a `data-theme` attribute on `<html>`.

## Approach

CSS custom properties (`--` vars) already define all design tokens in `:root`. Theme overrides are additional blocks in `app.css` that replace those tokens when `data-theme` is set on `<html>`. No new CSS files, no extra network requests.

## Themes

### `dark` (default)

No `data-theme` attribute. The existing `:root` block is unchanged.

### `blanc`

White, clean, modern. Purple accent (`#7c3aed`) is preserved from the dark theme.

| Variable | Value |
|---|---|
| `--bg-base` | `#ffffff` |
| `--bg-surface` | `#f5f5f5` |
| `--bg-elevated` | `#eeeeee` |
| `--border` | `#e0e0e0` |
| `--border-subtle` | `#ebebeb` |
| `--text` | `#1a1a1a` |
| `--text-muted` | `#555555` |
| `--text-dim` | `#888888` |
| `--text-bright` | `#000000` |
| `--text-subtle` | `#333333` |
| `--accent` | `#7c3aed` |
| `--accent-strong` | `#6d28d9` |
| `--accent-deep` | `#5b21b6` |
| `--muted` | `#999999` |
| `--danger` | `#b91c1c` |
| `--danger-bg` | `#fef2f2` |
| `--danger-border` | `#fecaca` |
| `--danger-text` | `#b91c1c` |
| `--success` | `#22c55e` |
| `--success-text` | `#15803d` |

### `old-steam`

Faithful recreation of the original Steam 2003 client palette: dark greenish-brown backgrounds, gold accents, warm off-white text. Source: Tecate/steam-2003 repository.

| Variable | Value |
|---|---|
| `--bg-base` | `#293021` |
| `--bg-surface` | `#4c5844` |
| `--bg-elevated` | `#3e4637` |
| `--border` | `#282e22` |
| `--border-subtle` | `#323828` |
| `--text` | `#dedfd6` |
| `--text-muted` | `#a0aa95` |
| `--text-dim` | `#889180` |
| `--text-bright` | `#ffffff` |
| `--text-subtle` | `#c6c3b8` |
| `--accent` | `#c6b652` |
| `--accent-strong` | `#9a8c38` |
| `--accent-deep` | `#7a6e2a` |
| `--muted` | `#5a6a50` |
| `--danger` | `#b91c1c` |
| `--danger-bg` | `#3b1219` |
| `--danger-border` | `#7f1d1d` |
| `--danger-text` | `#fca5a5` |
| `--success` | `#5a9e4e` |
| `--success-text` | `#7aaa5a` |

## Files Changed

### `src/main/resources/static/css/app.css`

Add two theme blocks after the existing `:root` block:

```css
[data-theme="blanc"] {
  /* all vars from blanc table */
}

[data-theme="old-steam"] {
  /* all vars from old-steam table */
}
```

### `src/main/resources/templates/fragments/head.html`

Add an inline `<script>` block **before** the `<link rel="stylesheet">` tag to apply the saved theme before CSS renders, preventing flash of unstyled content (FOUC):

```html
<script>
  (function(){
    var t = localStorage.getItem('theme');
    if (t) document.documentElement.setAttribute('data-theme', t);
  })();
</script>
```

### `src/main/resources/static/js/app.js`

Add theme management functions:

```js
function setTheme(name) {
  if (name === 'dark') {
    document.documentElement.removeAttribute('data-theme');
    localStorage.removeItem('theme');
  } else {
    document.documentElement.setAttribute('data-theme', name);
    localStorage.setItem('theme', name);
  }
  document.querySelectorAll('.theme-btn').forEach(b =>
    b.classList.toggle('active', b.dataset.theme === name)
  );
}
```

### `src/main/resources/templates/fragments/nav.html`

Add a floating theme selector button group, fixed at the bottom-right of the viewport. It must be placed **inside** the `<nav th:fragment="nav(active)">` element (as a last child) because Thymeleaf only includes the fragment element itself — a sibling div would be ignored. `position: fixed` means its location in the DOM does not affect its visual position.

```html
<div class="theme-switcher">
  <button class="theme-btn" data-theme="dark"      onclick="setTheme('dark')"     title="Dark">🌙</button>
  <button class="theme-btn" data-theme="blanc"     onclick="setTheme('blanc')"    title="Blanc">☀️</button>
  <button class="theme-btn" data-theme="old-steam" onclick="setTheme('old-steam')" title="Old Steam">🎮</button>
</div>
```

CSS for the floating button in `app.css`:

```css
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

The active button is highlighted on page load by reading `localStorage` in `setTheme` or by an `initThemeUI()` call in `app.js`.

## Initialization on page load

In `app.js`, add an `initThemeUI()` function called when the DOM is ready to mark the correct button as active:

```js
function initThemeUI() {
  var current = localStorage.getItem('theme') || 'dark';
  document.querySelectorAll('.theme-btn').forEach(b =>
    b.classList.toggle('active', b.dataset.theme === current)
  );
}
document.addEventListener('DOMContentLoaded', initThemeUI);
```

## Out of scope

- Server-side theme persistence (no DB column, no Spring session storage)
- Theme preview on hover
- Custom user-defined themes
- Animations between theme transitions
