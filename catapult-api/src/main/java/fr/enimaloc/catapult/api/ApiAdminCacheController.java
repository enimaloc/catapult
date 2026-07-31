package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.chat.DynamicCommandResolver;
import fr.enimaloc.catapult.chat.TwPlaceholderRegistry;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
import fr.enimaloc.catapult.repository.IgdbGameCclRepository;
import fr.enimaloc.catapult.repository.IgdbGameDetailsRepository;
import fr.enimaloc.catapult.repository.TwDefinitionRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.IgdbService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory caches (IGDB lookups, per-user resolved chat commands, the TW registry snapshot) have
 * no admin visibility today — a stale or wrong entry can only be cleared by restarting the whole
 * app. This lets an admin inspect and selectively evict individual entries instead.
 */
@RestController
@RequestMapping("/api/admin/caches")
@PreAuthorize("hasRole('ADMIN') or hasIpAddress('127.0.0.1') or hasIpAddress('::1')")
@RequiredArgsConstructor
public class ApiAdminCacheController {

    private final IgdbService igdbService;
    private final DynamicCommandResolver dynamicCommandResolver;
    private final TwPlaceholderRegistry twPlaceholderRegistry;
    private final IgdbGameDetailsRepository igdbGameDetailsRepository;
    private final IgdbGameCclRepository igdbGameCclRepository;
    private final TwDefinitionRepository twDefinitionRepository;
    private final ChatCommandDefinitionRepository chatCommandDefinitionRepository;
    private final UserAccountRepository userAccountRepository;

    @Value("${app.igdb.details-cache-ttl-hours:168}")
    private int detailsCacheTtlHours;

    private static final Set<String> IGDB_DETAILS_TTL_CACHES =
        Set.of("igdb-game-cache", "igdb-name-index", "igdb-exe-index");

    public record CacheSummaryDto(String name, int size, boolean deletable) {}
    public record CacheEntryDto(String key, String value) {}
    public record CacheEntryDetailDto(String key, Object detail, Long expiresInSeconds) {}

    @GetMapping
    public List<CacheSummaryDto> list() {
        return List.of(
            new CacheSummaryDto("igdb-game-cache", igdbService.getGameCache().size(), true),
            new CacheSummaryDto("igdb-name-index", igdbService.getNameIndex().size(), true),
            new CacheSummaryDto("igdb-exe-index", igdbService.getExeIndex().size(), true),
            new CacheSummaryDto("igdb-ccl-cache", igdbService.getCclCache().size(), true),
            new CacheSummaryDto("chat-command-user-cache", dynamicCommandResolver.cacheSnapshot().size(), true),
            new CacheSummaryDto("tw-known-paths", twPlaceholderRegistry.getKnownPaths().size(), false),
            new CacheSummaryDto("tw-all-options", twPlaceholderRegistry.getAllOptions().size(), false)
        );
    }

    @GetMapping("/{name}")
    public org.springframework.data.domain.Page<CacheEntryDto> entries(
            @PathVariable String name,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        List<CacheEntryDto> filtered = filter(allEntries(name), q);
        return paginate(filtered, page, size);
    }

    @GetMapping("/{name}/entry")
    public CacheEntryDetailDto entryDetail(@PathVariable String name, @RequestParam String key) {
        Object detail = resolveDetail(name, key);
        return new CacheEntryDetailDto(key, detail, expiresInSeconds(name, detail));
    }

    private Object resolveDetail(String name, String key) {
        return switch (name) {
            case "igdb-game-cache" -> igdbGameDetailsRepository.findById(key).orElse(null);
            case "igdb-name-index" -> igdbGameDetailsRepository.findById(igdbIdFromIndex(igdbService.getNameIndex(), key)).orElse(null);
            case "igdb-exe-index" -> igdbGameDetailsRepository.findById(igdbIdFromIndex(igdbService.getExeIndex(), key)).orElse(null);
            case "igdb-ccl-cache" -> igdbGameCclRepository.findById(key).orElse(null);
            case "chat-command-user-cache" -> chatCommandDefinitionRepository.findByUser(requireUser(key));
            case "tw-known-paths", "tw-all-options" -> twDefinitionRepository.findById(key).orElse(null);
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown cache " + name);
        };
    }

    private static String igdbIdFromIndex(Map<String, IgdbService.IgdbGame> index, String key) {
        IgdbService.IgdbGame game = index.get(key);
        if (game == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown entry " + key);
        }
        return game.id();
    }

    private fr.enimaloc.catapult.domain.UserAccount requireUser(String key) {
        return userAccountRepository.findById(parseUserId(key))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown user " + key));
    }

    private Long expiresInSeconds(String name, Object detail) {
        if (!IGDB_DETAILS_TTL_CACHES.contains(name)
                || !(detail instanceof fr.enimaloc.catapult.domain.IgdbGameDetails d)
                || d.getFetchedAt() == null) {
            return null;
        }
        Instant expiresAt = d.getFetchedAt().plus(Duration.ofHours(detailsCacheTtlHours));
        return Duration.between(Instant.now(), expiresAt).getSeconds();
    }

    private List<CacheEntryDto> allEntries(String name) {
        return switch (name) {
            case "igdb-game-cache" -> igdbService.getGameCache().entrySet().stream()
                .map(e -> new CacheEntryDto(e.getKey(), e.getValue())).toList();
            case "igdb-name-index" -> igdbService.getNameIndex().entrySet().stream()
                .map(e -> new CacheEntryDto(e.getKey(), e.getValue().id() + " / " + e.getValue().name())).toList();
            case "igdb-exe-index" -> igdbService.getExeIndex().entrySet().stream()
                .map(e -> new CacheEntryDto(e.getKey(), e.getValue().id() + " / " + e.getValue().name())).toList();
            case "igdb-ccl-cache" -> igdbService.getCclCache().entrySet().stream()
                .map(e -> new CacheEntryDto(e.getKey(), String.join(", ", e.getValue()))).toList();
            case "chat-command-user-cache" -> dynamicCommandResolver.cacheSnapshot().entrySet().stream()
                .map(e -> new CacheEntryDto(e.getKey().toString(), String.join(", ", e.getValue()))).toList();
            case "tw-known-paths" -> twPlaceholderRegistry.getKnownPaths().stream()
                .map(id -> new CacheEntryDto(id, id)).toList();
            case "tw-all-options" -> twPlaceholderRegistry.getAllOptions().stream()
                .map(o -> new CacheEntryDto(o.get("id"), o.get("label"))).toList();
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown cache " + name);
        };
    }

    private static List<CacheEntryDto> filter(List<CacheEntryDto> entries, String q) {
        if (q == null || q.isBlank()) {
            return entries;
        }
        String needle = q.toLowerCase(java.util.Locale.ROOT);
        return entries.stream()
            .filter(e -> e.key().toLowerCase(java.util.Locale.ROOT).contains(needle)
                      || e.value().toLowerCase(java.util.Locale.ROOT).contains(needle))
            .toList();
    }

    private static org.springframework.data.domain.Page<CacheEntryDto> paginate(List<CacheEntryDto> entries, int page, int size) {
        int fromIndex = Math.min(page * size, entries.size());
        int toIndex = Math.min(fromIndex + size, entries.size());
        return new org.springframework.data.domain.PageImpl<>(
            entries.subList(fromIndex, toIndex),
            org.springframework.data.domain.PageRequest.of(page, size),
            entries.size());
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> deleteEntry(@PathVariable String name, @RequestParam String key) {
        boolean removed = switch (name) {
            case "igdb-game-cache" -> igdbService.evictGameCache(key);
            case "igdb-name-index" -> igdbService.evictNameIndex(key);
            case "igdb-exe-index" -> igdbService.evictExeIndex(key);
            case "igdb-ccl-cache" -> igdbService.evictCclCache(key);
            case "chat-command-user-cache" -> dynamicCommandResolver.evictUser(parseUserId(key));
            case "tw-known-paths", "tw-all-options" ->
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cache " + name + " is read-only");
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown cache " + name);
        };
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    private static UUID parseUserId(String key) {
        try {
            return UUID.fromString(key);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid user id " + key);
        }
    }
}
