# Twitch Category Sweep Warmup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a sequential-ID sweep warmup (`/helix/games?id=0&id=1&...`) alongside the existing top-games prewarm, selectable via `app.twitch.prewarm-mode`.

**Architecture:** `prewarmCategoryCache()` is refactored into a dispatcher that calls `prewarmByTopGames()` (existing logic, extracted) and/or `prewarmBySweep()` (new sequential sweep) based on an enum property. The sweep populates `igdb_id` in `twitch_category_cache`; the top-games path leaves it null (unchanged).

**Tech Stack:** Spring Boot, Spring Data JPA, RestClient, JUnit 5 + Mockito, AssertJ

---

## File Map

| Action | File |
|--------|------|
| Modify | `src/main/java/fr/enimaloc/catapult/service/TwitchCategoryService.java` |
| Modify | `src/main/resources/application.properties` |
| Modify | `src/main/resources/application-mock.properties` |
| Modify | `src/test/java/fr/enimaloc/catapult/service/TwitchCategoryServiceTest.java` |

---

### Task 1: Add `PrewarmMode` enum and property, refactor dispatcher

**Files:**
- Modify: `src/main/java/fr/enimaloc/catapult/service/TwitchCategoryService.java`

- [ ] **Step 1: Add `PrewarmMode` enum as inner type and `@Value` field**

In `TwitchCategoryService`, add inside the class (after existing constants):

```java
public enum PrewarmMode { TOP, SWEEP, BOTH, NONE }

@Value("${app.twitch.prewarm-mode:BOTH}")
private PrewarmMode prewarmMode;
```

- [ ] **Step 2: Extract `prewarmByTopGames()` from `prewarmCategoryCache()`**

Rename the body of the existing `prewarmCategoryCache()` into a new private method:

```java
@SuppressWarnings("unchecked")
private void prewarmByTopGames() {
    String token = getOrRefreshAppToken();
    if (token.isBlank() || twitchClientId.isBlank()) {
        log.info("Twitch category prewarm skipped — client credentials not configured");
        return;
    }

    log.info("Twitch category prewarm started (/helix/games/top pagination)");
    String cursor = null;
    int total = 0;

    do {
        String uri = TWITCH_API_URL + "/games/top?first=" + BATCH_SIZE
                + (cursor != null ? "&after=" + cursor : "");
        cursor = null;

        try {
            Map<String, Object> response = restClient.get()
                    .uri(uri)
                    .header("Authorization", "Bearer " + token)
                    .header("Client-Id", twitchClientId)
                    .retrieve()
                    .body(Map.class);

            if (response == null) break;
            List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
            if (data == null || data.isEmpty()) break;

            List<TwitchCategoryCache> batch = data.stream()
                    .map(g -> new TwitchCategoryCache(
                            (String) g.get("id"),
                            (String) g.get("name"),
                            (String) g.get("box_art_url")))
                    .collect(Collectors.toList());
            cacheRepo.saveAll(batch);
            log.trace("Warm with: {}", batch.stream().map(cat -> "%s (%s)".formatted(cat.getName(), cat.getId())).collect(Collectors.joining(", ", "[", "]")));
            total += batch.size();

            Map<String, Object> pagination = (Map<String, Object>) response.get("pagination");
            if (pagination != null) {
                cursor = (String) pagination.get("cursor");
            }
        } catch (Exception e) {
            log.warn("Twitch category prewarm batch failed: {}", e.getMessage());
            break;
        }
    } while (cursor != null);

    log.info("Twitch category prewarm complete: {} categories cached", total);
}
```

- [ ] **Step 3: Replace `prewarmCategoryCache()` body with dispatcher**

```java
@Async
@EventListener(ApplicationReadyEvent.class)
public void prewarmCategoryCache() {
    switch (prewarmMode) {
        case TOP  -> prewarmByTopGames();
        case SWEEP -> prewarmBySweep();
        case BOTH -> { prewarmByTopGames(); prewarmBySweep(); }
        case NONE -> log.info("Twitch category prewarm disabled (prewarm-mode=NONE)");
    }
}
```

Remove the `@SuppressWarnings("unchecked")` from `prewarmCategoryCache()` — it is now on `prewarmByTopGames()` (shown above in Step 2).

- [ ] **Step 4: Verify compilation**

```bash
./gradlew compileJava
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/service/TwitchCategoryService.java
git commit -m "refactor: extract prewarmByTopGames and add PrewarmMode dispatcher"
```

---

### Task 2: Implement `prewarmBySweep()`

**Files:**
- Modify: `src/main/java/fr/enimaloc/catapult/service/TwitchCategoryService.java`

- [ ] **Step 1: Add `prewarmBySweep()` method**

Add the following private method in `TwitchCategoryService`, after `prewarmByTopGames()`:

```java
@SuppressWarnings("unchecked")
private void prewarmBySweep() {
    String token = getOrRefreshAppToken();
    if (token.isBlank() || twitchClientId.isBlank()) {
        log.info("Twitch category sweep skipped — client credentials not configured");
        return;
    }

    log.info("Twitch category sweep started (/helix/games sequential ID sweep)");
    int offset = 0;
    int total  = 0;

    while (true) {
        StringBuilder uri = new StringBuilder(TWITCH_API_URL + "/games");
        for (int i = offset; i < offset + BATCH_SIZE; i++) {
            uri.append(i == offset ? "?id=" : "&id=").append(i);
        }

        try {
            Map<String, Object> response = restClient.get()
                    .uri(uri.toString())
                    .header("Authorization", "Bearer " + token)
                    .header("Client-Id", twitchClientId)
                    .retrieve()
                    .body(Map.class);

            if (response == null) break;
            List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
            if (data == null || data.isEmpty()) break;

            List<TwitchCategoryCache> batch = data.stream()
                    .map(g -> {
                        TwitchCategoryCache entry = new TwitchCategoryCache(
                                (String) g.get("id"),
                                (String) g.get("name"),
                                (String) g.get("box_art_url"));
                        entry.setIgdbId((String) g.get("igdb_id"));
                        return entry;
                    })
                    .collect(Collectors.toList());
            cacheRepo.saveAll(batch);
            log.trace("Sweep batch [{}-{}]: {}", offset, offset + BATCH_SIZE - 1,
                    batch.stream().map(c -> "%s (%s)".formatted(c.getName(), c.getId()))
                         .collect(Collectors.joining(", ", "[", "]")));
            total += batch.size();
        } catch (Exception e) {
            log.warn("Twitch category sweep batch [{}-{}] failed: {}", offset, offset + BATCH_SIZE - 1, e.getMessage());
            break;
        }

        offset += BATCH_SIZE;
    }

    log.info("Twitch category sweep complete: {} categories cached", total);
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileJava
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/service/TwitchCategoryService.java
git commit -m "feat: implement prewarmBySweep using /helix/games sequential ID sweep"
```

---

### Task 3: Update properties files

**Files:**
- Modify: `src/main/resources/application.properties`
- Modify: `src/main/resources/application-mock.properties`

- [ ] **Step 1: Add `prewarm-mode` to `application.properties`**

Append after the existing `app.twitch.category-cache-ttl-hours=24` line:

```properties
app.twitch.prewarm-mode=BOTH
```

- [ ] **Step 2: Disable prewarm in mock profile**

Add to `application-mock.properties`:

```properties
app.twitch.prewarm-mode=NONE
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.properties src/main/resources/application-mock.properties
git commit -m "chore: add app.twitch.prewarm-mode property (default BOTH, NONE in mock)"
```

---

### Task 4: Update tests

**Files:**
- Modify: `src/test/java/fr/enimaloc/catapult/service/TwitchCategoryServiceTest.java`

The existing tests call `service.prewarmCategoryCache()` directly. After Task 1, the dispatcher requires `prewarmMode` to be set. Add a `@BeforeEach` field via `ReflectionTestUtils` and update/add tests.

- [ ] **Step 1: Set default `prewarmMode` in `setup()`**

In the existing `setup()` method, add:

```java
ReflectionTestUtils.setField(service, "prewarmMode", TwitchCategoryService.PrewarmMode.TOP);
```

This keeps the existing `prewarmCategoryCache_*` tests exercising the `TOP` path (top-games logic unchanged).

- [ ] **Step 2: Add test — `prewarmMode=NONE` skips all network calls**

```java
@Test
void prewarmCategoryCache_skipsWhenModeIsNone() {
    ReflectionTestUtils.setField(service, "prewarmMode", TwitchCategoryService.PrewarmMode.NONE);

    service.prewarmCategoryCache();

    verifyNoInteractions(cacheRepo);
    verify(restClient, never()).get();
}
```

- [ ] **Step 3: Add test — `prewarmMode=SWEEP` stops on first empty response**

```java
@Test
void prewarmCategoryCache_sweepStopsOnFirstEmptyBatch() {
    ReflectionTestUtils.setField(service, "prewarmMode", TwitchCategoryService.PrewarmMode.SWEEP);

    // First sweep batch returns 1 game; second returns empty → stop
    doReturn(Map.of(
        "data", List.of(
            Map.of("id", "743", "name", "Chess", "box_art_url", "https://img/chess.jpg", "igdb_id", "7")
        )
    )).doReturn(Map.of("data", List.of()))
       .when(responseSpec).body(Map.class);

    service.prewarmCategoryCache();

    verify(cacheRepo, times(1)).saveAll(argThat(list -> {
        List<TwitchCategoryCache> l = (List<TwitchCategoryCache>) list;
        return l.size() == 1 && "743".equals(l.get(0).getId()) && "7".equals(l.get(0).getIgdbId());
    }));
    verify(cacheRepo, times(1)).saveAll(anyList());
}
```

- [ ] **Step 4: Add test — `prewarmMode=SWEEP` populates `igdb_id`**

```java
@Test
void prewarmBySweep_populatesIgdbId() {
    ReflectionTestUtils.setField(service, "prewarmMode", TwitchCategoryService.PrewarmMode.SWEEP);

    doReturn(Map.of(
        "data", List.of(
            Map.of("id", "33214", "name", "Fortnite", "box_art_url", "https://img/fn.jpg", "igdb_id", "1905")
        )
    )).doReturn(Map.of("data", List.of()))
       .when(responseSpec).body(Map.class);

    service.prewarmCategoryCache();

    verify(cacheRepo).saveAll(argThat(list -> {
        List<TwitchCategoryCache> l = (List<TwitchCategoryCache>) list;
        return l.size() == 1 && "1905".equals(l.get(0).getIgdbId());
    }));
}
```

- [ ] **Step 5: Run all tests**

```bash
./gradlew test --tests "fr.enimaloc.catapult.service.TwitchCategoryServiceTest"
```
Expected: all tests PASS

- [ ] **Step 6: Commit**

```bash
git add src/test/java/fr/enimaloc/catapult/service/TwitchCategoryServiceTest.java
git commit -m "test: add TwitchCategoryServiceTest cases for PrewarmMode and sweep igdb_id"
```
