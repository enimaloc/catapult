# Pill Toggle Button — Design Spec

**Date:** 2026-05-15
**Scope:** `admin/members.html`, `static/css/app.css`

## Objectif

Fusionner les badges de statut et les boutons d'action dans la table des membres en un seul élément pill à deux segments, similaire à un split-button.

## Colonnes concernées

| Colonne | Actuel | Après |
|---------|--------|-------|
| Bot | badge ✓/✗ + bouton Activer/Désactiver | pill `[✓ Actif | Désactiver]` |
| Statut (mock) | badge ACTIVE + boutons Online/Offline | pill `[🔴 Live | ■ Offline]` ou `[⬛ Offline | ▶ Online]` |
| Statut (non-mock) | badge seul | inchangé |

## Structure HTML

Un seul `<form>` wrappant un `<div class="pill-toggle pill-toggle--{état}">` :

```html
<form th:action="..." method="post">
  <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
  <div class="pill-toggle pill-toggle--active">   <!-- ou pill-toggle--inactive, pill-toggle--live -->
    <span class="pill-toggle__label">✓ Actif</span>
    <button type="submit" class="pill-toggle__btn">Désactiver</button>
  </div>
</form>
```

## CSS

Nouvelles classes dans `app.css` (section Buttons) :

```css
.pill-toggle {
  display: inline-flex;
  border-radius: 9999px;
  overflow: hidden;
  border: 1px solid transparent;
  font-size: 12px;
  font-weight: 600;
}

.pill-toggle__label {
  padding: 4px 12px;
  display: flex;
  align-items: center;
}

.pill-toggle__btn {
  padding: 4px 12px;
  border: none;
  border-left: 1px solid rgba(0,0,0,0.15);
  cursor: pointer;
  font-size: inherit;
  font-weight: inherit;
  font-family: inherit;
  transition: opacity .15s;
}
.pill-toggle__btn:hover { opacity: .85; }

/* Chaque modificateur définit background + color sur .pill-toggle */
/* .pill-toggle__btn hérite la couleur et assombrit le fond */
.pill-toggle--active  { background: var(--success); color: var(--success-text); border-color: var(--success); }
.pill-toggle--inactive { background: var(--muted); color: var(--text-muted); border-color: var(--muted); }
.pill-toggle--live    { background: var(--danger); color: #fff; border-color: var(--danger); }
.pill-toggle--offline { background: var(--muted); color: var(--text-muted); border-color: var(--muted); }

.pill-toggle__btn { background: inherit; filter: brightness(0.85); }
```

### Modificateurs d'état

| Modificateur | Usage | Couleurs |
|---|---|---|
| `pill-toggle--active` | Bot actif | `--success` bg, `--success-text` color |
| `pill-toggle--inactive` | Bot inactif | `--muted` bg, `--text-muted` color |
| `pill-toggle--live` | Statut live | `--danger` bg, `#fff` color |
| `pill-toggle--offline` | Statut offline | `--muted` bg, `--text-muted` color |

Chaque modificateur définit `background` et `color` sur `.pill-toggle`, le bouton hérite et son fond est légèrement plus sombre via `filter: brightness(0.85)`.

## Mapping Thymeleaf

**Colonne Bot :**
- `pill-toggle--active` quand `member.botEnabled == true`
- `pill-toggle--inactive` quand `member.botEnabled == false`
- Label : `✓ Actif` / `✗ Inactif`
- Bouton : `Désactiver` / `Activer`
- Action : `/admin/members/{id}/bot/toggle`

**Colonne Statut (mock uniquement) :**
- `pill-toggle--live` quand `liveStatus[member.id] == true`
- `pill-toggle--offline` quand `liveStatus[member.id] == false`
- Label : `🔴 Live` / `⬛ Offline`
- Bouton : `■ Offline` / `▶ Online`
- Action : `/admin/members/{id}/twitch/offline` ou `/admin/members/{id}/twitch/online`

## Thème old-steam

Ajouter `.pill-toggle` et `.pill-toggle__btn` dans la liste des sélecteurs `border-radius: 0` existante.

## Fichiers modifiés

1. `src/main/resources/static/css/app.css` — nouvelles classes `.pill-toggle`
2. `src/main/resources/templates/admin/members.html` — colonnes Bot et Statut
