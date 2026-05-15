# Admin Members Page — Design Spec

**Date:** 2026-05-15
**Status:** Approved

## Summary

Add `/admin/members` page allowing the admin to view all `UserAccount` members, mock their Twitch stream and Steam game state (mock profile only), and impersonate their account via Spring Security's `SwitchUserFilter`.

This page replaces the existing `/mock/twitch` and `/mock/steam` pages, centralising member management under the admin area.

---

## Architecture

### New Files

| File | Purpose |
|------|---------|
| `web/AdminMembersController.java` | GET `/admin/members` — loads all members + stream states |
| `web/AdminMembersMockController.java` | POST mock actions — `@Profile("mock")` only |
| `templates/admin/members.html` | Thymeleaf template |

### Modified Files

| File | Change |
|------|--------|
| `security/SecurityConfig.java` | Register `SwitchUserFilter`, add `/admin/impersonate` and `/admin/impersonate/exit` routes |
| `templates/fragments/nav.html` | Add impersonation banner (visible when `ROLE_PREVIOUS_ADMINISTRATOR` present) |
| `web/MockSteamController.java` | Delete (logic moves to `AdminMembersMockController`) |
| `web/MockTwitchController.java` | Delete (logic moves to `AdminMembersMockController`) |

### Unchanged

`MockSteamApiClient`, `MockTwitchEventSubService` — business logic unchanged, only the web layer changes.

---

## Components & Data Flow

### `AdminMembersController`

```
GET /admin/members
  → UserAccountRepository.findAll()
  → StreamStateService (all stream states)
  → model: List<UserAccount> + Map<twitchId, StreamState>
  → render admin/members.html
```

No `@Profile` annotation — works in both prod and mock.

### `AdminMembersMockController` (`@Profile("mock")`)

All endpoints redirect to `redirect:/admin/members` on success.

| Method | Endpoint | Delegate |
|--------|----------|----------|
| POST | `/admin/members/{id}/twitch/online` | `MockTwitchEventSubService.setOnline(user)` |
| POST | `/admin/members/{id}/twitch/offline` | `MockTwitchEventSubService.setOffline(user)` |
| POST | `/admin/members/{id}/steam/set` | `MockSteamApiClient.setGameForUser(steamId, gameId, gameName)` |
| POST | `/admin/members/{id}/steam/clear` | `MockSteamApiClient.clearGameForUser(steamId)` |

### `members.html` Template

Table columns:
- Twitch username
- Steam ID (empty if not linked)
- Bot enabled (boolean badge)
- Status (`ACTIVE` / `PENDING_DELETION`)
- Created at
- **Mock actions** — column visible only when mock profile active (passed via model flag `isMockProfile`)
  - Twitch: Online / Offline buttons (show current state)
  - Steam: Set game form + Clear button (disabled if no steamId)
- **Impersonate** — POST form button on each row

---

## Impersonation

Uses Spring Security's built-in `SwitchUserFilter`.

### Required: `ImpersonationUserDetailsService`

The project uses OAuth2 + API key auth and has no `UserDetailsService` bean. A dedicated one must be created:

```java
@Service
public class ImpersonationUserDetailsService implements UserDetailsService {
    // loadUserByUsername(twitchUsername) → CatapultOAuth2User wrapping the UserAccount
}
```

Wired into `SwitchUserFilter` via `filter.setUserDetailsService(impersonationUserDetailsService)`.

### Configuration

```java
SwitchUserFilter filter = new SwitchUserFilter();
filter.setUserDetailsService(impersonationUserDetailsService);
filter.setSwitchUserUrl("/admin/impersonate");
filter.setExitUserUrl("/admin/impersonate/exit");
filter.setSuccessHandler((req, res, auth) -> res.sendRedirect("/app"));
filter.setFailureHandler((req, res, ex) -> res.sendRedirect("/admin/members?error"));
```

- `/admin/impersonate?username={twitchUsername}` — protected by `hasRole("ADMIN")`; switches session
- `/admin/impersonate/exit` — accessible to any authenticated user with `ROLE_PREVIOUS_ADMINISTRATOR` (handled by the filter itself, not a security rule); restores admin session and redirects to `/admin/members`
- The `username` parameter is `twitchUsername` (looked up by `ImpersonationUserDetailsService`)

### Impersonation Banner (nav fragment)

Visible when `ROLE_PREVIOUS_ADMINISTRATOR` is present in current authentication authorities:

```html
<div class="impersonation-banner">
  Vous êtes connecté en tant que @{twitchUsername}
  <form action="/admin/impersonate/exit" method="post">
    <button type="submit">Retour à l'admin</button>
  </form>
</div>
```

---

## Security Constraints

- `/admin/impersonate` is protected by `hasRole("ADMIN")` — impersonated users have `ROLE_USER` only, so they cannot re-impersonate
- Admin cannot impersonate themselves (guard check in controller before redirect)
- Mock POST endpoints return `404` if the target `UserAccount` does not exist
- Steam actions are disabled in the template if the member has no `steamId`
- CSRF tokens are included on all POST forms (standard Spring MVC behaviour)

---

## Error Handling

Consistent with existing admin controllers — simple redirects:
- Member not found → `redirect:/admin/members?error=notFound`
- Impersonation failure → `redirect:/admin/members?error=impersonateFailed`
- No complex exception handling or flash messages needed

---

## Out of Scope

- Editing `UserAccount` fields (username, botEnabled, status) — separate concern
- Pagination — member count is small for this single-admin tool
- Global mock state (set same game/state for all users) — not requested; old pages can be referenced if needed
