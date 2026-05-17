# Mock Steam Game Picker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the two plain-text mock Steam inputs in `/admin/members` with an IGDB autocomplete identical to the Twitch category picker.

**Architecture:** `IgdbService.searchGames()` wraps `IgdbClient.searchByName()`; a new `GET /admin/members/igdb/search` endpoint (profil `mock-steam`) exposes it as JSON; `gameSearch()` in `app.js` gains a `data-search-url` override so the same JS function works for both Twitch and IGDB; `members.html` swaps the two text inputs for the picker pattern.

**Tech Stack:** Spring Boot, Lombok `@RequiredArgsConstructor`, Thymeleaf, vanilla JS, IGDB via igdb-apicalypse, JUnit 5 + Mockito.

---

## File Map

| Action | File |
|--------|------|
| Modify | `src/main/java/fr/enimaloc/catapult/service/IgdbService.java` |
| Modify | `src/main/java/fr/enimaloc/catapult/web/AdminMembersMockController.java` |
| Modify | `src/main/resources/static/js/app.js` |
| Modify | `src/main/resources/templates/admin/members.html` |
| Modify (test) | `src/test/java/fr/enimaloc/catapult/web/AdminMembersMockControllerTest.java` |

---

### Task 1 — Add `searchGames()` to `IgdbService`

**Files:**
- Modify: `src/main/java/fr/enimaloc/catapult/service/IgdbService.java`

- [ ] **Add the method** after `findByWindowsExecutable()` (around line 238):

```java
public List<IgdbGame> searchGames(String query) {
    if (clientId.isBlank() || query.length() < 2) return List.of();
    String token = getOrRefreshAppToken();
    if (token.isBlank()) return List.of();
    return igdbClient.searchByName(query, token).stream()
        .map(g -> new IgdbGame(String.valueOf(g.getId()), g.getName()))
        .toList();
}
```

`IgdbClient.searchByName()` already limits to 5 results and returns `List<proto.Game>`. Each `Game` has `.getId()` (long) and `.getName()` (String). `IgdbGame` is already a `record` at the bottom of `IgdbService`.

- [ ] **Commit:**

```bash
git add src/main/java/fr/enimaloc/catapult/service/IgdbService.java
git commit -m "feat: add searchGames() to IgdbService"
```

---

### Task 2 — Search endpoint + tests (TDD)

**Files:**
- Modify: `src/test/java/fr/enimaloc/catapult/web/AdminMembersMockControllerTest.java`
- Modify: `src/main/java/fr/enimaloc/catapult/web/AdminMembersMockController.java`

- [ ] **Step 1 — Write the failing tests.** In `AdminMembersMockControllerTest.java`:

Add the import and `@Mock` field alongside the existing ones:
```java
import fr.enimaloc.catapult.service.IgdbService;
import java.util.List;
import java.util.Map;
```
```java
@Mock private IgdbService igdbService;
```

Add three new test methods at the end of the class:
```java
@Test
void searchIgdbGames_returnsResults() {
    when(igdbService.searchGames("zelda"))
        .thenReturn(List.of(new IgdbService.IgdbGame("1942", "The Legend of Zelda")));

    List<Map<String, String>> result = controller.searchIgdbGames("zelda");

    assertThat(result).hasSize(1);
    assertThat(result.get(0).get("id")).isEqualTo("1942");
    assertThat(result.get(0).get("name")).isEqualTo("The Legend of Zelda");
}

@Test
void searchIgdbGames_tooShortQuery_returnsEmpty() {
    List<Map<String, String>> result = controller.searchIgdbGames("z");
    assertThat(result).isEmpty();
    verifyNoInteractions(igdbService);
}

@Test
void searchIgdbGames_blankQuery_returnsEmpty() {
    List<Map<String, String>> result = controller.searchIgdbGames("  ");
    assertThat(result).isEmpty();
    verifyNoInteractions(igdbService);
}
```

- [ ] **Step 2 — Run tests, confirm compile failure:**

```bash
./gradlew test --tests "fr.enimaloc.catapult.web.AdminMembersMockControllerTest" 2>&1 | tail -15
```

Expected: compilation error — `cannot find symbol: method searchIgdbGames`.

- [ ] **Step 3 — Add `IgdbService` + endpoint to `AdminMembersMockController`.** Full updated file:

```java
package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.getter.MockSteamApiClient;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.IgdbService;
import fr.enimaloc.catapult.service.MockTwitchEventSubService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Controller
@Profile("mock-steam")
@RequestMapping("/admin/members")
@RequiredArgsConstructor
public class AdminMembersMockController {

    private final UserAccountRepository userAccountRepository;
    private final MockTwitchEventSubService mockTwitchEventSubService;
    private final Optional<MockSteamApiClient> mockSteamApiClient;
    private final IgdbService igdbService;

    @PostMapping("/{id}/twitch/online")
    public String setTwitchOnline(@PathVariable UUID id) {
        var user = userAccountRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        mockTwitchEventSubService.setOnline(user);
        return "redirect:/admin/members";
    }

    @PostMapping("/{id}/twitch/offline")
    public String setTwitchOffline(@PathVariable UUID id) {
        var user = userAccountRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        mockTwitchEventSubService.setOffline(user);
        return "redirect:/admin/members";
    }

    @PostMapping("/{id}/steam/set")
    public String setSteamGame(@PathVariable UUID id,
                               @RequestParam String gameId,
                               @RequestParam String gameName) {
        var user = userAccountRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (user.getSteamId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User has no Steam ID");
        }
        mockSteamApiClient.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "The server is not in mock mode"))
                .setGameForUser(user.getSteamId(), gameId.strip(), gameName.strip());
        return "redirect:/admin/members";
    }

    @PostMapping("/{id}/steam/clear")
    public String clearSteamGame(@PathVariable UUID id) {
        var user = userAccountRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (user.getSteamId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User has no Steam ID");
        }
        mockSteamApiClient.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "The server is not in mock mode"))
                .clearGameForUser(user.getSteamId());
        return "redirect:/admin/members";
    }

    @GetMapping(value = "/igdb/search", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<Map<String, String>> searchIgdbGames(@RequestParam String q) {
        if (q.isBlank() || q.length() < 2) return List.of();
        return igdbService.searchGames(q).stream()
                .map(g -> Map.of("id", g.id(), "name", g.name()))
                .toList();
    }
}
```

- [ ] **Step 4 — Run tests, confirm pass:**

```bash
./gradlew test --tests "fr.enimaloc.catapult.web.AdminMembersMockControllerTest" 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`, all 11 tests pass (8 existing + 3 new).

- [ ] **Step 5 — Commit:**

```bash
git add src/main/java/fr/enimaloc/catapult/web/AdminMembersMockController.java \
        src/test/java/fr/enimaloc/catapult/web/AdminMembersMockControllerTest.java
git commit -m "feat: add IGDB game search endpoint to mock admin controller"
```

---

### Task 3 — `data-search-url` support in `app.js`

**Files:**
- Modify: `src/main/resources/static/js/app.js`

- [ ] **Edit line 15** in `gameSearch()`:

Before:
```js
        fetch('/api/games/search?q=' + encodeURIComponent(q))
```

After:
```js
        fetch((input.dataset.searchUrl || '/api/games/search') + '?q=' + encodeURIComponent(q))
```

All existing callers (obs-setup, bindings) have no `data-search-url` attribute, so they fall back to `/api/games/search` unchanged.

- [ ] **Commit:**

```bash
git add src/main/resources/static/js/app.js
git commit -m "feat: support data-search-url override in gameSearch()"
```

---

### Task 4 — Update `members.html` Mock Steam column

**Files:**
- Modify: `src/main/resources/templates/admin/members.html`

- [ ] **Replace** the `<div th:if="${member.steamId != null}">` block inside the `<td th:if="${canMockSteam}">` cell (lines 73–85).

Current:
```html
<div th:if="${member.steamId != null}" style="display:flex;gap:6px">
    <form th:action="@{/admin/members/{id}/steam/set(id=${member.id})}" method="post"> <!-- nosemgrep -->
        <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
        <input type="text" name="gameId" placeholder="Game ID" style="width:80px;padding:2px 4px">
        <input type="text" name="gameName" placeholder="Nom du jeu" style="width:100px;padding:2px 4px">
        <button type="submit" class="btn btn-sm btn-primary">Set</button>
    </form>
    <form th:action="@{/admin/members/{id}/steam/clear(id=${member.id})}" method="post"> <!-- nosemgrep -->
        <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
        <button type="submit" class="btn btn-sm">Clear</button>
    </form>
</div>
```

Replace with:
```html
<div th:if="${member.steamId != null}" style="display:flex;gap:6px;align-items:flex-end">
    <form th:action="@{/admin/members/{id}/steam/set(id=${member.id})}" method="post"> <!-- nosemgrep -->
        <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
        <div style="position:relative">
            <input type="text"
                   th:id="'steam-search-' + ${member.id}"
                   placeholder="Rechercher un jeu…"
                   oninput="gameSearch(event)"
                   autocomplete="off"
                   th:attr="data-results-id='steam-results-' + ${member.id},
                            data-gameid-field='steam-gameId-' + ${member.id},
                            data-gamename-field='steam-gameName-' + ${member.id}"
                   data-search-url="/admin/members/igdb/search"
                   style="width:180px;padding:2px 4px">
            <ul th:id="'steam-results-' + ${member.id}" class="game-results"></ul>
        </div>
        <input type="hidden" name="gameId"   th:id="'steam-gameId-'   + ${member.id}">
        <input type="hidden" name="gameName" th:id="'steam-gameName-' + ${member.id}">
        <button type="submit" class="btn btn-sm btn-primary">Set</button>
    </form>
    <form th:action="@{/admin/members/{id}/steam/clear(id=${member.id})}" method="post"> <!-- nosemgrep -->
        <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
        <button type="submit" class="btn btn-sm">Clear</button>
    </form>
</div>
```

Note: `data-search-url` is static (same for all rows) so it doesn't need `th:attr`. The three dynamic data attributes (`data-results-id`, `data-gameid-field`, `data-gamename-field`) use `th:attr` to embed the member UUID. `gameSearch()` reads `dataset.searchUrl`, `dataset.resultsId`, `dataset.gameidField`, `dataset.gamenameField` via the standard camelCase conversion of `data-*` attributes.

- [ ] **Commit:**

```bash
git add src/main/resources/templates/admin/members.html
git commit -m "feat: replace mock steam text inputs with IGDB autocomplete"
```

---

## Spec Coverage Check

| Spec requirement | Task |
|------------------|------|
| Endpoint returns `[{id, name}]`, profil `mock-steam` | Task 2 |
| `q` blank or < 2 chars → empty | Task 2 (controller + service) |
| IGDB not configured → empty | Task 1 (`clientId.isBlank()`) |
| `gameSearch()` `data-search-url` fallback | Task 3 |
| `members.html` autocomplete pattern | Task 4 |
| POST set/clear unchanged | No change needed |
