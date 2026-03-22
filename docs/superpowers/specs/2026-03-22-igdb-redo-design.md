# IGDB Integration Redo — Design Spec
Date: 2026-03-22

## Context

The previous IGDB integration used an `IgdbApiClient` interface + `IgdbApiClientImpl` component,
with all methods left unimplemented (returning `List.of()`). The Flyway fix (`FlywayConfig.java`)
also had a `@ConditionalOnMissingBean` guard that was bypassed by Spring Boot 4.x's auto-config
creating a `Flyway` bean first, so our `flyway.migrate()` call was never reached.

This spec describes a complete redo: simpler architecture, fully implemented, with Flyway
reliably running on every startup.

---

## Goals

- Replace the interface + empty impl pair with two concrete classes: `IgdbClient` + `IgdbService`
- Implement all IGDB API calls via the `igdb-api-jvm` library (`IGDBWrapper`)
- Use an app-level (client_credentials) Twitch token — no user token dependency for IGDB calls
- Fix Flyway so migrations always run at startup under Spring Boot 4.x
- Keep the two-level cache (L1 memory + L2 DB with 24h TTL) and CCL suggestion logic

---

## Architecture

```
IgdbService (@Service)
  ├── manages Twitch app token (client_credentials, auto-refresh)
  ├── L1 cache: ConcurrentHashMap<lookupKey, IgdbGame>
  ├── L2 cache: IgdbGameCacheRepository (DB, 24h TTL)
  └── delegates API calls to IgdbClient (injected as concrete class)

IgdbClient (@Component)
  └── thin wrapper around IGDBWrapper.INSTANCE
      synchronized(IGDBWrapper.INSTANCE) covers both setCredentials() and API call
```

---

## Files

### Deleted
- `src/main/java/fr/esportline/catapult/service/IgdbApiClient.java`
- `src/main/java/fr/esportline/catapult/service/IgdbApiClientImpl.java`

### Created
- `src/main/java/fr/esportline/catapult/service/IgdbClient.java`

### Rewritten
- `src/main/java/fr/esportline/catapult/service/IgdbService.java`

### Modified
- `src/main/java/fr/esportline/catapult/config/FlywayConfig.java` — remove `@ConditionalOnMissingBean`
- `src/main/java/fr/esportline/catapult/service/BindingService.java` — remove `twitchAccessToken` parameter from all 3 IGDB calls; remove `getTwitchAccessToken()` call from `createWithIgdbResolution`
- `src/main/resources/application.properties`:
    - `spring.flyway.enabled=false`
    - remove `app.igdb.access-token` (no longer used; token is fetched at runtime)
    - remove `spring.flyway.locations` (managed by `FlywayConfig` directly, not Spring Boot auto-config)
- `src/main/resources/application-dev.properties`:
    - remove `spring.flyway.enabled` and `spring.flyway.locations` (must NOT override base with `true`)
    - remove `app.igdb.access-token`

### Unchanged
- `IgdbGameCacheEntry.java`, `IgdbGameCacheRepository.java`, `V4__igdb_game_cache.sql`

---

## IgdbClient

`@Component`. Thin, stateless wrapper around `IGDBWrapper.INSTANCE`. No cache, no token management.

```
Injected:
  @Value("${app.igdb.client-id:}") String clientId

State:
  String currentToken = ""   (tracks last token set on IGDBWrapper)

Thread safety:
  Every method uses synchronized(IGDBWrapper.INSTANCE) to wrap:
    1. setCredentials() if token changed (checked INSIDE the synchronized block)
    2. the actual ProtoRequestKt API call
  This prevents race conditions between concurrent callers swapping credentials mid-call.

Methods:

  findSourcesByName(String name, String token)
      → List<ExternalGameSource>  throws RequestException
      Endpoint: /external_game_sources
      Query: fields id,name; where name="<name>"; limit 1

  findExternalGamesByUid(String uid, long sourceId, String token)
      → List<ExternalGame>
      Endpoint: /external_games
      Where clause built internally:
        if sourceId >= 0: "external_game_source=<sourceId> & uid=\"<uid>\""
        else (fallback):  "category=1 & uid=\"<uid>\""
      Query: fields uid,game.id,game.name; limit 1
      Catches RequestException → log ERROR → return List.of()

  searchByName(String name, String token)
      → List<Game>
      Endpoint: /games (search mode)
      Query: search "<name>"; fields id,name; limit 5
      Catches RequestException → log ERROR → return List.of()

  fetchGameById(String igdbId, String fields, String token)
      → List<Game>
      Endpoint: /games
      Query: fields <fields>; where id=<Long.parseLong(igdbId)>; limit 1
      Catches RequestException → log ERROR → return List.of()

  fetchGamePage(int limit, int offset, String token)
      → List<Game>
      Endpoint: /games
      Query: fields id,name; sort aggregated_rating DESC; limit <limit>; offset <offset>
      Catches RequestException → log ERROR → return List.of()

Logging (all at DEBUG level):
  Before each call: "[IGDB] <endpoint> — query: <apicalypse query string>"
  After each call:  "[IGDB] <endpoint> — <n> result(s)"
  On RequestException: ERROR with full message
```

---

## IgdbService

`@Service @RequiredArgsConstructor`. Orchestrates token management, caching, and IGDB logic.

```
Injected (via @RequiredArgsConstructor):
  IgdbClient igdbClient                    (renamed from igdbApiClient: IgdbApiClient)
  IgdbGameCacheRepository cacheRepository
  RestClient restClient                    (used by getOrRefreshAppToken() for Twitch HTTP call)
```

### Token management

```
Fields:
  @Value("${app.igdb.client-id:}")     String clientId
  @Value("${twitch.client-secret:}")   String clientSecret
  volatile String appAccessToken
  volatile Instant tokenExpiresAt = Instant.EPOCH

getOrRefreshAppToken() → synchronized method
  If appAccessToken != null and now < tokenExpiresAt - 60s: return cached token
  Else: POST https://id.twitch.tv/oauth2/token?grant_type=client_credentials&client_id=...&client_secret=...
        Parse access_token + expires_in from Map<String, Object> response
        On error: log ERROR, return ""
```

### Startup (@PostConstruct init())

```
1. loadSteamSourceId()
     If clientId or clientSecret blank: skip
     token = getOrRefreshAppToken()
     If token blank: skip
     sources = igdbClient.findSourcesByName("Steam", token)
     If not empty: steamSourceId = sources.get(0).getId()
     Else: log WARN, steamSourceId stays -1 (triggers category=1 fallback in IgdbClient)

2. warmInMemoryCacheFromDb()
     Load all DB entries with cachedAt > now - cacheTtlHours
     For "name:..." keys: populate igdbNameIndex (normalized name → IgdbGame)
     All entries: populate igdbGameCache (igdbId → name) via putIfAbsent

3. preloadGameList()
     If clientId or clientSecret blank: log WARN, return
     token = getOrRefreshAppToken()
     If token blank: return
     Paginate fetchGamePage() up to preloadLimit (max 500 per page)
     Build newCache (igdbId → name) + newIndex (normalizedName → IgdbGame)
     Replace each map: clear() then putAll() — sequentially, not atomically.
     There is a brief window where igdbGameCache is updated but igdbNameIndex is not yet.
     This is acceptable: cache misses fall through to DB or API.
     Note: @Scheduled may fire concurrently with a running preload. This is tolerated —
     ConcurrentHashMap operations are individually thread-safe and the last writer wins.
```

### Public API (no token parameter — uses internal app token)

```
findBySteamAppId(String appId) → Optional<IgdbGame>
  key = "steam:<appId>"
  1. lookupInDb(key)  → hit: return immediately
  2. getOrRefreshAppToken()
  3. igdbClient.findExternalGamesByUid(appId, steamSourceId, token)
  4. If empty: return Optional.empty()
  5. Extract game.id + game.name → IgdbGame
  6. saveToDb(key, igdbGame)
  7. return Optional.of(igdbGame)

findByName(String gameName) → Optional<IgdbGame>
  key = "name:<normalize(gameName)>"
  1. igdbNameIndex.get(normalize(gameName))  → hit: return immediately
  2. lookupInDb(key)  → hit: put in igdbNameIndex, return
  3. getOrRefreshAppToken()
  4. igdbClient.searchByName(gameName, token)
  5. If empty: return Optional.empty()
  6. IgdbGame = first result
  7. igdbNameIndex.put(normalize(gameName), igdbGame)
  8. saveToDb(key, igdbGame)
  9. return Optional.of(igdbGame)

suggestCcls(String igdbGameId) → Set<TwitchCcl>
  getOrRefreshAppToken()
  igdbClient.fetchGameById(igdbGameId, "themes.name,keywords.name,age_ratings.rating", token)
  Collect themes + keywords into Set<String> (lowercase)
  Map to TwitchCcl values via app.mapping.ccl.* properties

getGameCache() → Map<String, String>
  Return Collections.unmodifiableMap(igdbGameCache)
```

### Scheduled preload

```
@Scheduled(fixedRate = preloadTtlHours, timeUnit = HOURS)
preloadGameList() — same as startup step 3, replaces cache sequentially (cache misses tolerated)
```

### Cache helpers

```
lookupInDb(String key) → Optional<IgdbGame>
  cacheRepository.findById(key)
  Filter: cachedAt > now - cacheTtlHours
  Map to IgdbGame(igdbId, name)

saveToDb(String key, IgdbGame game)
  cacheRepository.save(new IgdbGameCacheEntry(key, game.id(), game.name()))
  (IgdbGameCacheEntry constructor sets cachedAt = Instant.now())
```

---

## BindingService Changes

Remove the user Twitch token from all IGDB resolution calls.

```java
// Before:
String twitchAccessToken = getTwitchAccessToken(user);
Optional<IgdbService.IgdbGame> byAppId = igdbService.findBySteamAppId(detectedGame.getSourceId(), twitchAccessToken);
igdbService.findByName(detectedGame.getSourceName(), twitchAccessToken);
igdbService.suggestCcls(igdbGame.get().id(), twitchAccessToken);

// After:
// Remove twitchAccessToken variable and getTwitchAccessToken() call
Optional<IgdbService.IgdbGame> byAppId = igdbService.findBySteamAppId(detectedGame.getSourceId());
igdbService.findByName(detectedGame.getSourceName());
igdbService.suggestCcls(igdbGame.get().id());
```

`getTwitchAccessToken(UserAccount)` can be deleted entirely from `BindingService`.

---

## Flyway Fix

### Root cause
Spring Boot 4.x auto-config creates a `Flyway` bean when `spring.flyway.enabled=true`.
This causes `@ConditionalOnMissingBean` in `FlywayConfig.flyway()` to skip our custom bean
entirely. As a result, the explicit `flyway.migrate()` call in our config is never reached.

### Fix
1. `application.properties` and `application-dev.properties`:
   Set (or inherit) `spring.flyway.enabled=false` to prevent Spring Boot from creating its own `Flyway` bean.
   The dev profile must NOT override this with `true`.
2. `FlywayConfig.java`: remove `@ConditionalOnMissingBean`.
   Our `flyway()` bean is now always registered, always calls `migrate()`, and is the sole Flyway manager.

---

## Data Flow — findBySteamAppId("570")

```
IgdbService.findBySteamAppId("570")
  → lookupInDb("steam:570")             — cache miss
  → getOrRefreshAppToken()              → "abc123"
  → igdbClient.findExternalGamesByUid("570", steamSourceId=7, "abc123")
      synchronized(IGDBWrapper.INSTANCE) {
        setCredentials(clientId, "abc123")   // if token changed
        ProtoRequestKt.externalGames(wrapper, query)
        // query: where external_game_source=7 & uid="570"; fields uid,game.id,game.name; limit 1
      }
      → List<ExternalGame> with one entry
  → game = results.get(0).getGame()
  → IgdbGame("1234", "Dark Souls")
  → saveToDb("steam:570", IgdbGame)
  → return Optional.of(IgdbGame("1234", "Dark Souls"))
```

---

## Error Handling

| Scenario | Behavior |
|---|---|
| `clientId` blank | `IgdbService` returns empty / empty set immediately without calling `IgdbClient` |
| Token fetch fails | Log ERROR, return `""`, callers skip API call |
| `RequestException` in API call | `IgdbClient` logs ERROR, returns `List.of()` (except `findSourcesByName` which propagates) |
| `findSourcesByName` throws | `IgdbService.loadSteamSourceId()` catches, logs WARN, `steamSourceId` stays -1 |
| Preload fails | Log WARN, app starts normally with empty preload cache |

---

## Configuration Properties

```properties
# Token fetched at runtime via client_credentials — no static access-token needed
app.igdb.client-id=...           # defaults to twitch.client-id if not set
twitch.client-secret=...         # used for client_credentials token fetch

# Cache
app.igdb.cache-ttl-hours=24
app.igdb.preload-ttl-hours=24
app.igdb.preload-limit=500

# Flyway — disable Spring Boot auto-config, FlywayConfig handles migration
spring.flyway.enabled=false

# CCL mapping
app.mapping.ccl.violence=ViolentGraphic
app.mapping.ccl.mature=MatureGame
app.mapping.ccl.sexual_content=SexualThemes
app.mapping.ccl.drugs=DrugUse
app.mapping.ccl.gambling=Gambling
app.mapping.ccl.profanity=ProfanityVulgarity
app.mapping.ccl.language_barrier=LanguageBarrier
```
