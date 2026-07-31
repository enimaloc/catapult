package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.chat.DynamicCommandResolver;
import fr.enimaloc.catapult.chat.TwPlaceholderRegistry;
import fr.enimaloc.catapult.service.IgdbService;
import lombok.RequiredArgsConstructor;
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

import java.util.List;
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

    public record CacheSummaryDto(String name, int size, boolean deletable) {}
    public record CacheEntryDto(String key, String value) {}

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
    public List<CacheEntryDto> entries(@PathVariable String name) {
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
