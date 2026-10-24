package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.AdminPage;
import fr.enimaloc.catapult.common.dto.admin.DetectedGameDto;
import fr.enimaloc.catapult.common.dto.admin.GameMappingDto;
import fr.enimaloc.catapult.common.dto.admin.GameMappingRequest;
import fr.enimaloc.catapult.common.dto.admin.GameMappingResult;
import fr.enimaloc.catapult.service.http.ApiClient;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The mock's admin game mappings: one store for the whole application. Answers like catapult-api: 201 for a
 * created mapping (400 for the MANUAL platform, a missing ID or a missing category; 409 for a game already
 * mapped), 404 for an unknown mapping. Lists the mappings last changed first, and the detected games without a
 * mapping most streamers first, in pages of {@code pageSize} (small by default, so the mock shows paging).
 */
class MockGameMappingsStore {
    static final int DEFAULT_PAGE_SIZE = 2;
    private static final int DETECTED_MAX = 20;

    private final Clock clock;
    private final int pageSize;
    private final List<GameMappingDto> mappings = new ArrayList<>();
    private final List<DetectedGameDto> detected = new ArrayList<>();

    MockGameMappingsStore() {
        this(Clock.systemUTC(), DEFAULT_PAGE_SIZE);
    }

    MockGameMappingsStore(Clock clock, int pageSize) {
        this.clock = clock;
        this.pageSize = pageSize;
        Instant now = clock.instant();
        detected.add(new DetectedGameDto("STEAM", "730", "Counter-Strike 2", 14));
        detected.add(new DetectedGameDto("XBOX", "1942", "Halo Infinite", 6));
        detected.add(new DetectedGameDto("STEAM", "1245620", "Elden Ring", 12));
        detected.add(new DetectedGameDto("STEAM", "413150", "Stardew Valley", 5));
        detected.add(new DetectedGameDto("XBOX", "2043", "Forza Horizon 5", 3));
        mappings.add(new GameMappingDto(UUID.randomUUID(), "STEAM", "730", "Counter-Strike 2", "32399",
                "Counter-Strike", null, null, 14, now.minus(2, ChronoUnit.DAYS), "enimaloc"));
        mappings.add(new GameMappingDto(UUID.randomUUID(), "XBOX", "1942", "Halo Infinite", "506416",
                "Halo Infinite", java.util.Set.of("violent-graphic"), java.util.Set.of("violence"), 6,
                now.minus(1, ChronoUnit.DAYS), "enimaloc"));
    }

    synchronized AdminPage<GameMappingDto> list(int page) {
        return page(mappings.stream().sorted(Comparator.comparing(GameMappingDto::updatedAt).reversed()).toList(), page);
    }

    synchronized AdminPage<DetectedGameDto> missing(int page) {
        return page(detected.stream().filter(d -> findBySource(d.sourceType(), d.sourceId()) == null)
                .sorted(Comparator.comparingLong(DetectedGameDto::streamerCount).reversed()).toList(), page);
    }

    /** The games detected on {@code sourceType} whose ID starts with, or whose name contains, {@code q}; none without a platform. */
    synchronized List<DetectedGameDto> detected(String sourceType, String q) {
        if (sourceType == null || sourceType.isBlank()) return List.of();
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        return detected.stream()
                .filter(d -> d.sourceType().equalsIgnoreCase(sourceType))
                .filter(d -> d.sourceId().toLowerCase(Locale.ROOT).startsWith(needle)
                        || d.sourceName().toLowerCase(Locale.ROOT).contains(needle))
                .limit(DETECTED_MAX)
                .toList();
    }

    synchronized ApiClient.Result<GameMappingResult> create(GameMappingRequest request, String updatedBy) {
        if (request == null || invalid(request.sourceType(), request.sourceId(), request.twitchGameId())) {
            return new ApiClient.Result<>(400, null);
        }
        String sourceId = request.sourceId().trim();
        if (findBySource(request.sourceType(), sourceId) != null) return new ApiClient.Result<>(409, null);
        DetectedGameDto game = detected.stream()
                .filter(d -> d.sourceType().equals(request.sourceType()) && d.sourceId().equals(sourceId))
                .findFirst().orElse(null);
        GameMappingDto mapping = new GameMappingDto(UUID.randomUUID(), request.sourceType(), sourceId,
                game == null ? null : game.sourceName(), request.twitchGameId(), request.twitchGameName(),
                request.ccls(), request.tws(), game == null ? 0 : game.streamerCount(), clock.instant(), updatedBy);
        mappings.add(mapping);
        return new ApiClient.Result<>(201, result(mapping, request.applyLive()));
    }

    /** Changes the category and the overrides; the game itself (platform and ID) stays. */
    synchronized ApiClient.Result<GameMappingResult> update(UUID id, GameMappingRequest request, String updatedBy) {
        int index = indexOf(id);
        if (index < 0) return new ApiClient.Result<>(404, null);
        GameMappingDto current = mappings.get(index);
        if (request == null || request.twitchGameId() == null || request.twitchGameId().isBlank()) {
            return new ApiClient.Result<>(400, null);
        }
        GameMappingDto changed = new GameMappingDto(id, current.sourceType(), current.sourceId(), current.sourceName(),
                request.twitchGameId(), request.twitchGameName(), request.ccls(), request.tws(),
                current.bindingCount(), clock.instant(), updatedBy);
        mappings.set(index, changed);
        return new ApiClient.Result<>(200, result(changed, request.applyLive()));
    }

    synchronized ApiClient.Result<GameMappingResult> delete(UUID id, boolean applyLive) {
        int index = indexOf(id);
        if (index < 0) return new ApiClient.Result<>(404, null);
        GameMappingDto removed = mappings.remove(index);
        return new ApiClient.Result<>(200, new GameMappingResult(null, (int) removed.bindingCount(), applyLive ? 1 : 0, 0));
    }

    private <T> AdminPage<T> page(List<T> all, int page) {
        int from = (int) Math.min((long) Math.max(page, 0) * pageSize, all.size());
        int to = Math.min(from + pageSize, all.size());
        return new AdminPage<>(all.subList(from, to), Math.max(page, 0), (all.size() + pageSize - 1) / pageSize, all.size());
    }

    private static boolean invalid(String sourceType, String sourceId, String twitchGameId) {
        return sourceType == null || sourceType.isBlank() || "MANUAL".equalsIgnoreCase(sourceType)
                || sourceId == null || sourceId.isBlank() || twitchGameId == null || twitchGameId.isBlank();
    }

    private static GameMappingResult result(GameMappingDto mapping, boolean applyLive) {
        return new GameMappingResult(mapping, (int) mapping.bindingCount(), applyLive ? 1 : 0, 0);
    }

    private GameMappingDto findBySource(String sourceType, String sourceId) {
        return mappings.stream()
                .filter(m -> m.sourceType().equals(sourceType) && m.sourceId().equals(sourceId))
                .findFirst().orElse(null);
    }

    private int indexOf(UUID id) {
        for (int i = 0; i < mappings.size(); i++) {
            if (mappings.get(i).id().equals(id)) return i;
        }
        return -1;
    }
}
