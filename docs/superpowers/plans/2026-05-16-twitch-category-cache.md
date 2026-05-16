# Twitch Category Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cache Twitch game categories locally in DB (populated at startup via sequential `/helix/games` sweeps + lazy fallback), and enrich the autocomplete UI with box-art thumbnails across all three Twitch category search forms.

**Architecture:** A new `TwitchCategoryService` owns the DB cache (`twitch_category_cache`), manages its own Twitch app-access token, and exposes `searchCategories(String)`. `TwitchService.searchCategories` delegates to it; `AppController` is unchanged. All three search UIs (`app.js`, `global-process-rules.html`, `obs-setup.html`) are updated to render `<img> + name`.

**Tech Stack:** Spring Boot 3, Spring Data JPA, Flyway, Mockito (unit tests), Thymeleaf + vanilla JS.

---

## File Map

| Path | Action |
|------|--------|
| `src/main/resources/db/migration/V17__twitch_category_cache.sql` | Create |
| `src/main/java/fr/enimaloc/catapult/domain/TwitchCategoryCache.java` | Create |
| `src/main/java/fr/enimaloc/catapult/repository/TwitchCategoryCacheRepository.java` | Create |
| `src/main/java/fr/enimaloc/catapult/service/TwitchCategoryService.java` | Create |
| `src/main/java/fr/enimaloc/catapult/service/TwitchService.java` | Modify — add `TwitchCategoryService` dep, delegate `searchCategories`, add `boxArtUrl` to `TwitchCategory` record |
| `src/main/java/fr/enimaloc/catapult/web/AppController.java` | Modify — inject `TwitchCategoryService`, add `boxArtUrl` assertion in test |
| `src/main/resources/application.properties` | Modify — add TTL property |
| `src/test/resources/application.properties` | Modify — add TTL property |
| `src/test/java/fr/enimaloc/catapult/service/TwitchCategoryServiceTest.java` | Create |
| `src/test/java/fr/enimaloc/catapult/web/AppControllerTest.java` | Modify — update `TwitchCategory` ctor + assert `boxArtUrl` |
| `src/main/resources/static/css/app.css` | Modify — add thumbnail styles |
| `src/main/resources/static/js/app.js` | Modify — `gameSearch` renders thumbnail |
| `src/main/resources/templates/app.html` | Modify — `debouncedSearch` renders thumbnail |
| `src/main/resources/templates/admin/global-process-rules.html` | Modify — `adminSearch` renders thumbnail |
| `src/main/resources/templates/fragments/obs-setup.html` | Modify — `obsSearch` renders thumbnail |

---

### Task 1: Flyway Migration V17

**Files:**
- Create: `src/main/resources/db/migration/V17__twitch_category_cache.sql`

- [ ] **Step 1: Create the migration**

```sql
-- ============================================================
-- V17 — Twitch category cache : cache local des catégories Twitch
-- ============================================================

CREATE TABLE twitch_category_cache (
    id          VARCHAR(32)  NOT NULL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    box_art_url VARCHAR(512),
    igdb_id     VARCHAR(32),
    cached_at   TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_twitch_category_name      ON twitch_category_cache (lower(name));
CREATE INDEX idx_twitch_category_cached_at ON twitch_category_cache (cached_at);
```

- [ ] **Step 2: Verify migration applies at startup**

Run the application (or `./gradlew flywayMigrate`) and confirm no Flyway error. The table should appear in the DB:
```sql
\d twitch_category_cache
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V17__twitch_category_cache.sql
git commit -m "feat: add V17 migration for twitch_category_cache table"
```

---

### Task 2: Domain Entity + Repository

**Files:**
- Create: `src/main/java/fr/enimaloc/catapult/domain/TwitchCategoryCache.java`
- Create: `src/main/java/fr/enimaloc/catapult/repository/TwitchCategoryCacheRepository.java`

- [ ] **Step 1: Create the JPA entity**

```java
package fr.enimaloc.catapult.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "twitch_category_cache")
@Getter
@Setter
@NoArgsConstructor
public class TwitchCategoryCache {

    @Id
    @Column(name = "id")
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(name = "box_art_url", length = 512)
    private String boxArtUrl;

    @Column(name = "igdb_id")
    private String igdbId;

    @Column(name = "cached_at", nullable = false)
    private Instant cachedAt;

    public TwitchCategoryCache(String id, String name, String boxArtUrl) {
        this.id        = id;
        this.name      = name;
        this.boxArtUrl = boxArtUrl;
        this.cachedAt  = Instant.now();
    }
}
```

- [ ] **Step 2: Create the repository**

```java
package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.TwitchCategoryCache;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface TwitchCategoryCacheRepository extends JpaRepository<TwitchCategoryCache, String> {

    List<TwitchCategoryCache> findByNameContainingIgnoreCaseAndCachedAtAfter(
            String name, Instant since, Pageable pageable);
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/domain/TwitchCategoryCache.java \
        src/main/java/fr/enimaloc/catapult/repository/TwitchCategoryCacheRepository.java
git commit -m "feat: add TwitchCategoryCache entity and repository"
```

---

### Task 3: TwitchCategoryService — searchCategories

**Files:**
- Create: `src/main/java/fr/enimaloc/catapult/service/TwitchCategoryService.java`
- Create: `src/test/java/fr/enimaloc/catapult/service/TwitchCategoryServiceTest.java`

- [ ] **Step 1: Write failing tests**

```java
package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.TwitchCategoryCache;
import fr.enimaloc.catapult.repository.TwitchCategoryCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TwitchCategoryServiceTest {

    @Mock private TwitchCategoryCacheRepository cacheRepo;
    @Mock private RestClient restClient;

    @Mock private RestClient.RequestHeadersUriSpec getSpec;
    @Mock private RestClient.RequestHeadersSpec headersSpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    @InjectMocks private TwitchCategoryService service;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "twitchClientId",     "test-client");
        ReflectionTestUtils.setField(service, "twitchClientSecret", "test-secret");
        ReflectionTestUtils.setField(service, "cacheTtlHours",      24);

        // Stub app-token fetch used in searchCategories live fallback
        RestClient.RequestBodyUriSpec postSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec    = mock(RestClient.RequestBodySpec.class);
        doReturn(postSpec).when(restClient).post();
        doReturn(bodySpec).when(postSpec).uri(anyString());
        doReturn(responseSpec).when(bodySpec).retrieve();
        doReturn(Map.of("access_token", "app-token", "expires_in", 3600))
            .when(responseSpec).body(Map.class);

        doReturn(getSpec).when(restClient).get();
        doReturn(headersSpec).when(getSpec).uri(anyString());
        doReturn(headersSpec).when(headersSpec).header(anyString(), anyString());
        doReturn(responseSpec).when(headersSpec).retrieve();
    }

    @Test
    void searchCategories_hitsCacheWhenFresh() {
        TwitchCategoryCache cached = new TwitchCategoryCache("123", "Zelda", "https://img/zelda.jpg");
        when(cacheRepo.findByNameContainingIgnoreCaseAndCachedAtAfter(
                eq("zelda"), any(Instant.class), any(PageRequest.class)))
            .thenReturn(List.of(cached));

        List<TwitchService.TwitchCategory> results = service.searchCategories("zelda");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("123");
        assertThat(results.get(0).name()).isEqualTo("Zelda");
        assertThat(results.get(0).boxArtUrl()).isEqualTo("https://img/zelda.jpg");
        verify(restClient, never()).get(); // no live call
    }

    @Test
    void searchCategories_callsLiveWhenCacheEmpty() {
        when(cacheRepo.findByNameContainingIgnoreCaseAndCachedAtAfter(
                any(), any(), any())).thenReturn(List.of());

        doReturn(Map.of("data", List.of(
            Map.of("id", "456", "name", "Fortnite", "box_art_url", "https://img/fn.jpg")
        ))).when(responseSpec).body(Map.class);

        List<TwitchService.TwitchCategory> results = service.searchCategories("fortnite");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("456");
        verify(cacheRepo).saveAll(anyList());
    }

    @Test
    void searchCategories_returnEmptyWhenLiveUnavailableAndCacheMiss() {
        when(cacheRepo.findByNameContainingIgnoreCaseAndCachedAtAfter(
                any(), any(), any())).thenReturn(List.of());
        doReturn(null).when(responseSpec).body(Map.class);

        List<TwitchService.TwitchCategory> results = service.searchCategories("anything");

        assertThat(results).isEmpty();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "fr.enimaloc.catapult.service.TwitchCategoryServiceTest" 2>&1 | tail -20
```
Expected: compilation error — `TwitchCategoryService` does not exist yet.

- [ ] **Step 3: Create TwitchCategoryService**

```java
package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.TwitchCategoryCache;
import fr.enimaloc.catapult.repository.TwitchCategoryCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

@Slf4j
@Service
@DependsOn("flyway")
@RequiredArgsConstructor
public class TwitchCategoryService {

    private static final String TWITCH_TOKEN_URL = "https://id.twitch.tv/oauth2/token";
    private static final String TWITCH_API_URL   = "https://api.twitch.tv/helix";
    private static final int    BATCH_SIZE       = 100;
    private static final int    AUTOCOMPLETE_MAX = 8;

    private final TwitchCategoryCacheRepository cacheRepo;
    private final RestClient restClient;

    @Value("${twitch.client-id:}")
    private String twitchClientId;

    @Value("${twitch.client-secret:}")
    private String twitchClientSecret;

    @Value("${app.twitch.category-cache-ttl-hours:24}")
    private int cacheTtlHours;

    private volatile String appToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    // -----------------------------------------------------------------------
    // Startup prewarm (Task 4)
    // -----------------------------------------------------------------------

    @Async
    @PostConstruct
    public void prewarmCategoryCache() {
        // Implemented in Task 4
    }

    // -----------------------------------------------------------------------
    // Autocomplete search
    // -----------------------------------------------------------------------

    public List<TwitchService.TwitchCategory> searchCategories(String query) {
        if (query == null || query.isBlank()) return List.of();

        Instant cutoff = Instant.now().minusSeconds(cacheTtlHours * 3600L);
        List<TwitchCategoryCache> fromDb = cacheRepo.findByNameContainingIgnoreCaseAndCachedAtAfter(
                query, cutoff, PageRequest.of(0, AUTOCOMPLETE_MAX));

        if (!fromDb.isEmpty()) {
            log.debug("Twitch category cache hit for '{}'", query);
            return fromDb.stream()
                    .map(e -> new TwitchService.TwitchCategory(e.getId(), e.getName(), e.getBoxArtUrl()))
                    .toList();
        }

        return fetchFromSearchAndStore(query);
    }

    // -----------------------------------------------------------------------
    // Live search fallback
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<TwitchService.TwitchCategory> fetchFromSearchAndStore(String query) {
        String token = getOrRefreshAppToken();
        if (token.isBlank() || twitchClientId.isBlank()) return List.of();

        try {
            Map<String, Object> response = restClient.get()
                    .uri(TWITCH_API_URL + "/search/categories?query="
                         + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8)
                         + "&first=" + AUTOCOMPLETE_MAX)
                    .header("Authorization", "Bearer " + token)
                    .header("Client-Id", twitchClientId)
                    .retrieve()
                    .body(Map.class);

            if (response == null) return List.of();

            List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
            if (data == null || data.isEmpty()) return List.of();

            List<TwitchCategoryCache> toStore = data.stream()
                    .map(g -> new TwitchCategoryCache(
                            (String) g.get("id"),
                            (String) g.get("name"),
                            (String) g.get("box_art_url")))
                    .collect(Collectors.toList());
            cacheRepo.saveAll(toStore);

            return toStore.stream()
                    .map(e -> new TwitchService.TwitchCategory(e.getId(), e.getName(), e.getBoxArtUrl()))
                    .toList();

        } catch (Exception e) {
            log.warn("Twitch category live search failed for '{}': {}", query, e.getMessage());
            return List.of();
        }
    }

    // -----------------------------------------------------------------------
    // App token management
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    synchronized String getOrRefreshAppToken() {
        if (appToken != null && Instant.now().isBefore(tokenExpiresAt.minusSeconds(60))) {
            return appToken;
        }
        if (twitchClientId.isBlank() || twitchClientSecret.isBlank()) return "";
        try {
            Map<String, Object> resp = restClient.post()
                    .uri(TWITCH_TOKEN_URL + "?client_id=" + twitchClientId
                         + "&client_secret=" + twitchClientSecret
                         + "&grant_type=client_credentials")
                    .retrieve()
                    .body(Map.class);

            if (resp == null) return "";
            appToken = (String) resp.get("access_token");
            Number expiresIn = (Number) resp.get("expires_in");
            tokenExpiresAt = expiresIn != null
                    ? Instant.now().plusSeconds(expiresIn.longValue())
                    : Instant.now().plusSeconds(3600);
            return appToken != null ? appToken : "";
        } catch (Exception e) {
            log.error("Failed to fetch Twitch app token: {}", e.getMessage());
            return "";
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "fr.enimaloc.catapult.service.TwitchCategoryServiceTest" 2>&1 | tail -20
```
Expected: `3 tests passed`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/service/TwitchCategoryService.java \
        src/test/java/fr/enimaloc/catapult/service/TwitchCategoryServiceTest.java
git commit -m "feat: add TwitchCategoryService with DB-backed searchCategories"
```

---

### Task 4: TwitchCategoryService — Startup Prewarm

**Files:**
- Modify: `src/main/java/fr/enimaloc/catapult/service/TwitchCategoryService.java`
- Modify: `src/test/java/fr/enimaloc/catapult/service/TwitchCategoryServiceTest.java`

- [ ] **Step 1: Add prewarm test**

Add to `TwitchCategoryServiceTest`:

```java
@Test
void prewarmCategoryCache_fetchesBatchesAndStopsOnEmpty() {
    // First batch (IDs 0-99) returns 2 games; second batch returns empty → stop
    doReturn(Map.of("data", List.of(
        Map.of("id", "1", "name", "Game A", "box_art_url", "https://img/a.jpg"),
        Map.of("id", "2", "name", "Game B", "box_art_url", "https://img/b.jpg")
    ))).doReturn(Map.of("data", List.of()))
       .when(responseSpec).body(Map.class);

    service.prewarmCategoryCache();

    verify(cacheRepo, times(1)).saveAll(argThat(list ->
        ((List<?>) list).size() == 2));
    // Only 1 saveAll call because second batch is empty
    verify(cacheRepo, times(1)).saveAll(anyList());
}

@Test
void prewarmCategoryCache_skipsWhenClientIdBlank() {
    ReflectionTestUtils.setField(service, "twitchClientId", "");

    service.prewarmCategoryCache();

    verifyNoInteractions(cacheRepo);
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "fr.enimaloc.catapult.service.TwitchCategoryServiceTest" 2>&1 | tail -20
```
Expected: `prewarmCategoryCache_*` tests fail (empty body in `@PostConstruct`).

- [ ] **Step 3: Implement prewarmCategoryCache**

Replace the empty `prewarmCategoryCache` method in `TwitchCategoryService.java`:

```java
@Async
@PostConstruct
public void prewarmCategoryCache() {
    String token = getOrRefreshAppToken();
    if (token.isBlank() || twitchClientId.isBlank()) {
        log.info("Twitch category prewarm skipped — client credentials not configured");
        return;
    }

    log.info("Twitch category prewarm started (sequential ID sweep)");
    long offset  = 0;
    int  total   = 0;

    while (true) {
        String idParams = LongStream.range(offset, offset + BATCH_SIZE)
                .mapToObj(id -> "id=" + id)
                .collect(Collectors.joining("&"));

        List<TwitchCategoryCache> batch = fetchBatchByIds(idParams, token);
        if (batch.isEmpty()) {
            log.info("Twitch category prewarm complete: {} categories cached", total);
            break;
        }
        cacheRepo.saveAll(batch);
        total  += batch.size();
        offset += BATCH_SIZE;
    }
}

@SuppressWarnings("unchecked")
private List<TwitchCategoryCache> fetchBatchByIds(String idParams, String token) {
    try {
        Map<String, Object> response = restClient.get()
                .uri(TWITCH_API_URL + "/games?" + idParams)
                .header("Authorization", "Bearer " + token)
                .header("Client-Id", twitchClientId)
                .retrieve()
                .body(Map.class);

        if (response == null) return List.of();
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        if (data == null) return List.of();

        return data.stream()
                .map(g -> new TwitchCategoryCache(
                        (String) g.get("id"),
                        (String) g.get("name"),
                        (String) g.get("box_art_url")))
                .collect(Collectors.toList());
    } catch (Exception e) {
        log.warn("Twitch category prewarm batch failed: {}", e.getMessage());
        return List.of();
    }
}
```

Also add the missing import at the top of the file:
```java
import java.util.stream.LongStream;
```

- [ ] **Step 4: Run all TwitchCategoryServiceTest tests**

```bash
./gradlew test --tests "fr.enimaloc.catapult.service.TwitchCategoryServiceTest" 2>&1 | tail -20
```
Expected: `5 tests passed`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/service/TwitchCategoryService.java \
        src/test/java/fr/enimaloc/catapult/service/TwitchCategoryServiceTest.java
git commit -m "feat: implement TwitchCategoryService startup prewarm with sequential ID sweep"
```

---

### Task 5: Update TwitchService — Enrich TwitchCategory + Delegate

**Files:**
- Modify: `src/main/java/fr/enimaloc/catapult/service/TwitchService.java`
- Modify: `src/test/java/fr/enimaloc/catapult/web/AppControllerTest.java`

- [ ] **Step 1: Update `TwitchCategory` record and `searchCategories` in TwitchService**

In `TwitchService.java`:

**Change the record** (line ~166):
```java
// Before:
public record TwitchCategory(String id, String name) {}

// After:
public record TwitchCategory(String id, String name, String boxArtUrl) {}
```

**Add `TwitchCategoryService` dependency** — add field after existing `@Mock` fields:
```java
// At the top of the class, in the @RequiredArgsConstructor constructor fields:
private final TwitchCategoryService twitchCategoryService;
```

**Replace `searchCategories` method body** (the entire method ~135-165):
```java
public List<TwitchCategory> searchCategories(UserAccount user, String query) {
    return twitchCategoryService.searchCategories(query);
}
```

(Remove the old implementation that called `/search/categories` live — it moves entirely into `TwitchCategoryService`.)

- [ ] **Step 2: Update `AppControllerTest` for the enriched TwitchCategory**

In `AppControllerTest.java`, find the test `getGameSearch_returnsTwitchCategories` and update:

```java
// Before:
when(twitchService.searchCategories(eq(userAccount), eq("fortnite")))
    .thenReturn(List.of(new TwitchService.TwitchCategory("1234", "Fortnite")));

// After:
when(twitchService.searchCategories(eq(userAccount), eq("fortnite")))
    .thenReturn(List.of(new TwitchService.TwitchCategory("1234", "Fortnite", "https://img.example.com/fn.jpg")));
```

Also add assertion for `boxArtUrl` in the same test:
```java
.andExpect(jsonPath("$[0].boxArtUrl").value("https://img.example.com/fn.jpg"))
```

- [ ] **Step 3: Run impacted tests**

```bash
./gradlew test --tests "fr.enimaloc.catapult.web.AppControllerTest.getGameSearch_returnsTwitchCategories" 2>&1 | tail -20
```
Expected: `1 test passed`.

- [ ] **Step 4: Run full test suite to check for regressions**

```bash
./gradlew test 2>&1 | tail -30
```
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/service/TwitchService.java \
        src/test/java/fr/enimaloc/catapult/web/AppControllerTest.java
git commit -m "feat: delegate TwitchService.searchCategories to TwitchCategoryService and add boxArtUrl"
```

---

### Task 6: TTL Configuration Property

**Files:**
- Modify: `src/main/resources/application.properties`
- Modify: `src/test/resources/application.properties`

- [ ] **Step 1: Add property to main application.properties**

In `src/main/resources/application.properties`, add after the existing IGDB cache properties:
```properties
app.twitch.category-cache-ttl-hours=24
```

- [ ] **Step 2: Add property to test application.properties**

In `src/test/resources/application.properties`, add:
```properties
app.twitch.category-cache-ttl-hours=24
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.properties \
        src/test/resources/application.properties
git commit -m "feat: add app.twitch.category-cache-ttl-hours configuration property"
```

---

### Task 7: CSS — Thumbnail Styles

**Files:**
- Modify: `src/main/resources/static/css/app.css`

- [ ] **Step 1: Find existing `.game-results li` rule**

In `app.css` around line 732:
```css
.game-results li { padding: 8px 12px; cursor: pointer; border-bottom: 1px solid var(--border-subtle); color: var(--text); }
```

- [ ] **Step 2: Replace with thumbnail-aware rule**

```css
.game-results li { display: flex; align-items: center; gap: 8px; padding: 6px 12px; cursor: pointer; border-bottom: 1px solid var(--border-subtle); color: var(--text); }
.game-results li img { width: 30px; height: 40px; object-fit: cover; border-radius: 2px; flex-shrink: 0; }
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/css/app.css
git commit -m "feat: add thumbnail styles for game-results autocomplete list"
```

---

### Task 8: JS — Shared gameSearch Function (app.js + app.html)

**Files:**
- Modify: `src/main/resources/static/js/app.js`
- Modify: `src/main/resources/templates/app.html`

- [ ] **Step 1: Update `gameSearch` in app.js to render thumbnail**

In `app.js`, find the `gameSearch` function's `data.forEach` block:

```js
// Before:
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

Replace with:
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

- [ ] **Step 2: Update `debouncedSearch` in app.html to render thumbnail**

In `app.html`, find the `data.forEach` block inside `fetchSuggestions`:

```js
// Before:
data.forEach(game => {
    const li = document.createElement('li');
    li.textContent = game.name;
    li.addEventListener('click', () => {
        document.getElementById('twitchGameId-' + id).value = game.id;
        document.getElementById('twitchGameName-' + id).value = game.name;
        document.getElementById('gameSearch-' + id).value = game.name;
        results.style.display = 'none';
    });
    results.appendChild(li);
});
```

Replace with:
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
        document.getElementById('twitchGameId-' + id).value = game.id;
        document.getElementById('twitchGameName-' + id).value = game.name;
        document.getElementById('gameSearch-' + id).value = game.name;
        results.style.display = 'none';
    });
    results.appendChild(li);
});
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/js/app.js \
        src/main/resources/templates/app.html
git commit -m "feat: render box art thumbnail in game autocomplete suggestions (app.js + app.html)"
```

---

### Task 9: JS — Admin Template Thumbnail

**Files:**
- Modify: `src/main/resources/templates/admin/global-process-rules.html`

- [ ] **Step 1: Update `adminSearch` to render thumbnail**

In `global-process-rules.html` around line 185, find the `data.forEach` block:

```js
// Before:
data.forEach(function(game) {
    var li = document.createElement('li');
    li.textContent = game.name;
    li.addEventListener('click', function() {
        document.getElementById('twitchGameId-admin').value = game.id;
        document.getElementById('twitchGameName-admin').value = game.name;
        document.getElementById('adminGameSearch').value = game.name;
        results.style.display = 'none';
    });
    results.appendChild(li);
});
```

Replace with:
```js
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
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/templates/admin/global-process-rules.html
git commit -m "feat: render box art thumbnail in admin game search suggestions"
```

---

### Task 10: JS — OBS Setup Template Thumbnail

**Files:**
- Modify: `src/main/resources/templates/fragments/obs-setup.html`

- [ ] **Step 1: Update `obsSearch` to render thumbnail**

In `obs-setup.html` around line 203, find the `data.forEach` block:

```js
// Before:
data.forEach(function(game) {
    var li = document.createElement('li');
    li.textContent = game.name;
    li.addEventListener('click', function() {
        document.getElementById('twitchGameId-obs').value = game.id;
        document.getElementById('twitchGameName-obs').value = game.name;
        document.getElementById('obsGameSearch').value = game.name;
        results.style.display = 'none';
    });
    results.appendChild(li);
});
```

Replace with:
```js
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
        document.getElementById('twitchGameId-obs').value = game.id;
        document.getElementById('twitchGameName-obs').value = game.name;
        document.getElementById('obsGameSearch').value = game.name;
        results.style.display = 'none';
    });
    results.appendChild(li);
});
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/templates/fragments/obs-setup.html
git commit -m "feat: render box art thumbnail in OBS setup game search suggestions"
```

---

## Self-Review Checklist

### Spec Coverage

| Spec Requirement | Task |
|-----------------|------|
| Table `twitch_category_cache` with `id, name, box_art_url, igdb_id, cached_at` | Task 1 (SQL), Task 2 (entity) |
| `V17__twitch_category_cache.sql` Flyway migration | Task 1 |
| `TwitchCategoryCacheRepository.findByNameContainingIgnoreCaseAndCachedAtAfter` | Task 2 |
| `TwitchCategoryService.prewarmCategoryCache` — sequential ID sweep, stop on empty | Task 4 |
| `TwitchCategoryService.searchCategories` — DB first, live fallback, upsert | Task 3 |
| TTL via `app.twitch.category-cache-ttl-hours` | Task 6 |
| `TwitchCategory` record + `boxArtUrl` | Task 5 |
| `TwitchService.searchCategories` delegates | Task 5 |
| `/api/games/search` returns `boxArtUrl` | Task 5 (record change propagates via Jackson) |
| CSS thumbnail styles | Task 7 |
| `app.js gameSearch` renders thumbnail | Task 8 |
| `app.html debouncedSearch` renders thumbnail | Task 8 |
| `global-process-rules.html adminSearch` renders thumbnail | Task 9 |
| `obs-setup.html obsSearch` renders thumbnail | Task 10 |

**Gap found:** `igdb_id` is nullable and never set in these tasks — it is intentionally left for future enrichment (noted in spec as out of scope for prewarm).

### Placeholder Scan
None found — every step has concrete code.

### Type Consistency
- `TwitchService.TwitchCategory` uses `boxArtUrl` (camelCase) throughout — Task 3, 4, 5 all use the same name.
- Repository method `findByNameContainingIgnoreCaseAndCachedAtAfter` is defined in Task 2 and called identically in Task 3.
- `fetchBatchByIds(String idParams, String token)` defined and called only in Task 4.
- `getOrRefreshAppToken()` defined in Task 3 and called in Task 4 — consistent.
