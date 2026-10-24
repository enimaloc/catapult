package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.common.dto.admin.DetectedGameDto;
import fr.enimaloc.catapult.common.dto.admin.GameMappingDto;
import fr.enimaloc.catapult.common.dto.admin.GameMappingRequest;
import fr.enimaloc.catapult.common.dto.admin.GameMappingResult;
import fr.enimaloc.catapult.service.binding.AdminGameMappingService;
import fr.enimaloc.catapult.service.notification.AdminEventPublisher;
import fr.enimaloc.catapult.service.twitch.TwitchCategory;
import fr.enimaloc.catapult.service.twitch.TwitchCategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * The admin game mappings: a detected game (platform + its ID) set to a Twitch category for every streamer, the
 * detected games still missing one, and the lookups of the mapping form (detected games, Twitch categories).
 */
@RestController
@RequestMapping("/api/admin/game-mappings")
@PreAuthorize("hasRole('ADMIN') or hasIpAddress('127.0.0.1') or hasIpAddress('::1')")
@RequiredArgsConstructor
public class ApiAdminGameMappingController {

    private final AdminGameMappingService service;
    private final TwitchCategoryService categoryService;
    private final ApiUserResolver userResolver;

    @Autowired(required = false)
    private AdminEventPublisher events;

    /** The mappings, last changed first. */
    @GetMapping
    public Page<GameMappingDto> list(@RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "25") int size) {
        return service.list(page, size);
    }

    @PostMapping
    public ResponseEntity<GameMappingResult> create(@RequestBody(required = false) GameMappingRequest body,
                                                    @AuthenticationPrincipal Jwt jwt) {
        GameMappingResult result = service.create(body, userResolver.viewer(jwt));
        if (events != null) events.gameMappingCreated(result.mapping());
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PutMapping("/{id}")
    public GameMappingResult update(@PathVariable UUID id, @RequestBody(required = false) GameMappingRequest body,
                                    @AuthenticationPrincipal Jwt jwt) {
        GameMappingResult result = service.update(id, body, userResolver.viewer(jwt));
        if (events != null) events.gameMappingUpdated(result.mapping());
        return result;
    }

    @DeleteMapping("/{id}")
    public GameMappingResult delete(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean applyLive) {
        GameMappingResult result = service.delete(id, applyLive);
        if (events != null) events.gameMappingDeleted(id.toString());
        return result;
    }

    /** The detected games without a mapping that have an INCOMPLETE binding, most streamers first. */
    @GetMapping("/missing")
    public Page<DetectedGameDto> missing(@RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "25") int size) {
        return service.missing(page, size);
    }

    /** The form's game suggestions: games detected on the platform whose ID starts with, or name contains, {@code q}. */
    @GetMapping("/detected")
    public List<DetectedGameDto> detected(@RequestParam(required = false) String sourceType,
                                          @RequestParam(defaultValue = "") String q) {
        return service.detected(sourceType, q);
    }

    /** The Twitch category search of the form, with no channel (the streamers' one goes through theirs). */
    @GetMapping("/categories")
    public List<TwitchCategory> categories(@RequestParam(required = false) String q) {
        if (q == null || q.isBlank()) return List.of();
        return categoryService.searchCategories(q.trim());
    }
}
