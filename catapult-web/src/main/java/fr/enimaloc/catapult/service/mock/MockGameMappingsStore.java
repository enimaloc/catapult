package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.AdminPage;
import fr.enimaloc.catapult.common.dto.admin.DetectedGameDto;
import fr.enimaloc.catapult.common.dto.admin.GameMappingDto;
import fr.enimaloc.catapult.common.dto.admin.GameMappingRequest;
import fr.enimaloc.catapult.common.dto.admin.GameMappingResult;
import fr.enimaloc.catapult.common.dto.admin.IgdbGameOption;
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
 * mapped), 404 for an unknown mapping. A mapping pinned to an IGDB game without a category takes the game's
 * Twitch category when the mock's IGDB list knows one (400 otherwise), as catapult-api deduces it. Lists the mappings last changed first, and the detected games without a
 * mapping most streamers first, in pages of {@code pageSize} (small by default, so the mock shows paging).
 */
class MockGameMappingsStore {
    static final int DEFAULT_PAGE_SIZE = 2;
    private static final int DETECTED_MAX = 20;

    /** An IGDB game of the mock's fixed list, with the Twitch category catapult-api would deduce for it (none: null). */
    private record IgdbGame(String id, String name, int year, String twitchId, String twitchName) {
        IgdbGameOption option() {
            return new IgdbGameOption(id, name, year, null);
        }
    }

    private static final List<IgdbGame> IGDB_GAMES = List.of(
            new IgdbGame("123", "World of Warcraft", 2004, "18122", "World of Warcraft"),
            new IgdbGame("417650", "World of Warcraft: Forever", 2030, null, null),
            new IgdbGame("1279", "Hearthstone", 2014, "138585", "Hearthstone"),
            new IgdbGame("125174", "Overwatch", 2016, "515025", "Overwatch"),
            new IgdbGame("125165", "Diablo IV", 2023, "515024", "Diablo IV"),
            new IgdbGame("120", "Diablo III", 2012, "313558", "Diablo III"),
            new IgdbGame("142803", "Diablo II: Resurrected", 2021, "1788326126", "Diablo II: Resurrected"),
            new IgdbGame("239", "StarCraft II: Wings of Liberty", 2010, "490422", "StarCraft II"),
            new IgdbGame("7313", "Heroes of the Storm", 2015, "32959", "Heroes of the Storm"),
            new IgdbGame("111650", "Warcraft III: Reforged", 2020, "12924", "Warcraft III"),
            new IgdbGame("1942", "Counter-Strike 2", 2023, "32399", "Counter-Strike"),
            new IgdbGame("1020", "Elden Ring", 2022, "512953", "Elden Ring"),
            new IgdbGame("9999", "Obscure Prototype", 2019, null, null));

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
        // The Blizzard titles the migration seeds: each pinned to its IGDB game
        String[][] blizzard = {
                {"WoW", "18122", "World of Warcraft", "123"}, {"WTCG", "138585", "Hearthstone", "1279"},
                {"Pro", "515025", "Overwatch", "125174"}, {"Fen", "515024", "Diablo IV", "125165"},
                {"D3", "313558", "Diablo III", "120"}, {"OSI", "1788326126", "Diablo II: Resurrected", "142803"},
                {"S2", "490422", "StarCraft II", "239"}, {"Hero", "32959", "Heroes of the Storm", "7313"},
                {"W3", "12924", "Warcraft III", "111650"}};
        for (String[] b : blizzard) {
            mappings.add(new GameMappingDto(UUID.randomUUID(), "BATTLENET", b[0], null, b[1], b[2], null, null, 0,
                    now.minus(10, ChronoUnit.DAYS), "enimaloc", false, b[3], igdbName(b[3])));
        }
        mappings.add(new GameMappingDto(UUID.randomUUID(), "STEAM", "431960", "Wallpaper Engine", null, null,
                null, null, 0, now.minus(3, ChronoUnit.DAYS), "enimaloc", true, null, null));
    }

    /** The mock's IGDB games whose name contains {@code q}; none for a blank {@code q}. */
    List<IgdbGameOption> igdbGames(String q) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return List.of();
        return IGDB_GAMES.stream().filter(g -> g.name().toLowerCase(Locale.ROOT).contains(needle))
                .map(IgdbGame::option).limit(DETECTED_MAX).toList();
    }

    private static IgdbGame igdbGame(String id) {
        return blank(id) ? null : IGDB_GAMES.stream().filter(g -> g.id().equals(id)).findFirst().orElse(null);
    }

    private static String igdbName(String id) {
        IgdbGame game = igdbGame(id);
        return game == null ? null : game.name();
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
        if (request == null || invalid(request.sourceType(), request.sourceId(), request.twitchGameId(),
                request.ignoredByDefault(), request.igdbId())) {
            return new ApiClient.Result<>(400, null);
        }
        String sourceId = request.sourceId().trim();
        if (findBySource(request.sourceType(), sourceId) != null) return new ApiClient.Result<>(409, null);
        DetectedGameDto game = detected.stream()
                .filter(d -> d.sourceType().equals(request.sourceType()) && d.sourceId().equals(sourceId))
                .findFirst().orElse(null);
        String[] category = category(request);
        GameMappingDto mapping = new GameMappingDto(UUID.randomUUID(), request.sourceType(), sourceId,
                game == null ? null : game.sourceName(), category[0], category[1],
                request.ccls(), request.tws(), game == null ? 0 : game.streamerCount(), clock.instant(), updatedBy,
                request.ignoredByDefault(), request.igdbId(), igdbName(request.igdbId()));
        mappings.add(mapping);
        return new ApiClient.Result<>(201, result(mapping, request.applyLive()));
    }

    /** Changes the category and the overrides; the game itself (platform and ID) stays. */
    synchronized ApiClient.Result<GameMappingResult> update(UUID id, GameMappingRequest request, String updatedBy) {
        int index = indexOf(id);
        if (index < 0) return new ApiClient.Result<>(404, null);
        GameMappingDto current = mappings.get(index);
        if (request == null || unresolved(request.twitchGameId(), request.ignoredByDefault(), request.igdbId())) {
            return new ApiClient.Result<>(400, null);
        }
        String[] category = category(request);
        GameMappingDto changed = new GameMappingDto(id, current.sourceType(), current.sourceId(), current.sourceName(),
                category[0], category[1], request.ccls(), request.tws(),
                current.bindingCount(), clock.instant(), updatedBy, request.ignoredByDefault(),
                request.igdbId(), igdbName(request.igdbId()));
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

    /**
     * No category is only valid for a game ignored by default, or one pinned to an IGDB game whose Twitch category
     * can be deduced, as catapult-api checks it.
     */
    private static boolean invalid(String sourceType, String sourceId, String twitchGameId, boolean ignoredByDefault,
                                   String igdbId) {
        return blank(sourceType) || "MANUAL".equalsIgnoreCase(sourceType)
                || blank(sourceId) || unresolved(twitchGameId, ignoredByDefault, igdbId);
    }

    private static boolean unresolved(String twitchGameId, boolean ignoredByDefault, String igdbId) {
        if (!blank(twitchGameId) || ignoredByDefault) return false;
        IgdbGame game = igdbGame(igdbId);
        return game == null || game.twitchId() == null;
    }

    /** The request's category (id, name), else the one deduced from its IGDB game, else none. */
    private static String[] category(GameMappingRequest request) {
        if (!blank(request.twitchGameId())) return new String[]{request.twitchGameId(), request.twitchGameName()};
        IgdbGame game = request.ignoredByDefault() ? null : igdbGame(request.igdbId());
        return game == null ? new String[]{null, null} : new String[]{game.twitchId(), game.twitchName()};
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
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
