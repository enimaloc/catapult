# Design — Twitch Category Sweep Warmup

**Date:** 2026-05-16
**Status:** Approved

## Context

`TwitchCategoryService` already prewarns the `twitch_category_cache` table using `/helix/games/top` (top games by viewer count). This endpoint does not return `igdb_id`, leaving that column unpopulated.

A second warmup strategy using `/helix/games?id=0&id=1&...` sweeps sequential Twitch game IDs to discover all games (not just trending ones) and populates `igdb_id` from the response.

## Goal

Add a configurable prewarm mode that can run the existing top-games strategy, the new sequential sweep, or both.

## Property

```properties
app.twitch.prewarm-mode=BOTH   # TOP | SWEEP | BOTH | NONE
```

Default: `BOTH` — preserves current behaviour while enabling the sweep.

## Architecture

### Entry point

`prewarmCategoryCache()` (existing `@Async @EventListener(ApplicationReadyEvent.class)`) dispatches based on `prewarmMode`:

- `TOP`  → `prewarmByTopGames()`
- `SWEEP` → `prewarmBySweep()`
- `BOTH` → `prewarmByTopGames()` then `prewarmBySweep()`
- `NONE` → log and return

### `prewarmByTopGames()` (extracted from current code)

Existing `/helix/games/top` cursor-pagination logic, unchanged.

### `prewarmBySweep()`

Sequential ID sweep:

```
offset = 0
loop:
    ids = [offset, offset+1, ..., offset+99]  // 100 IDs per batch
    GET /helix/games?id={ids[0]}&id={ids[1]}&...
    if response.data is empty → STOP
    for each game: save TwitchCategoryCache(id, name, boxArtUrl, igdbId)
    offset += 100
```

Stopping condition: first batch returning 0 games terminates the sweep.

`igdb_id` is populated from the `igdb_id` field in the `/helix/games` response (not available from `/helix/games/top`).

### Data model

`TwitchCategoryCache` already has `igdb_id` column (V17 migration). The sweep warmup populates it; the top-games warmup leaves it null (no change to existing behaviour).

## Implementation Plan

Files to modify:
- `TwitchCategoryService.java` — extract `prewarmByTopGames()`, add `prewarmBySweep()`, update `prewarmCategoryCache()` dispatcher, add `@Value prewarmMode`
- `application.properties` — add `app.twitch.prewarm-mode=BOTH`
- `application-mock.properties` — add `app.twitch.prewarm-mode=NONE` (avoid network calls in mock profile)

No new DB migration needed.

## Trade-offs

| | TOP | SWEEP |
|---|---|---|
| Speed | Fast (5–10 pages) | Slow (N×100 IDs until empty) |
| Coverage | Trending games only | All games with sequential IDs |
| igdb_id populated | No | Yes |
