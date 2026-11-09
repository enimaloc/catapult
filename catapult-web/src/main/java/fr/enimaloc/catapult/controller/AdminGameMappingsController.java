package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.DetectedGameDto;
import fr.enimaloc.catapult.common.dto.admin.GameMappingRequest;
import fr.enimaloc.catapult.common.dto.admin.GameMappingResult;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import fr.enimaloc.catapult.common.dto.admin.TwitchCategoryDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The admin game mappings tab's actions (create, change and delete a mapping) and the lookups of its form
 * (detected games, Twitch categories), forwarded to catapult-api with the session's JWT. The actions answer
 * catapult-api's status (201 created, 200; 400 invalid; 404 unknown; 409 already mapped) and its result as JSON;
 * 502 when it is unreachable.
 */
@RestController
@RequestMapping("/admin/game-mappings")
@RequiredArgsConstructor
public class AdminGameMappingsController {
    private final ApiService apiService;
    private final ModelFiller filler;

    @PostMapping
    public ResponseEntity<GameMappingResult> create(@RequestBody GameMappingRequest body) {
        AdminAccess.require(filler);
        return answer(apiService.adminCreateGameMapping(body));
    }

    @PutMapping("/{id}")
    public ResponseEntity<GameMappingResult> update(@PathVariable UUID id, @RequestBody GameMappingRequest body) {
        AdminAccess.require(filler);
        return answer(apiService.adminUpdateGameMapping(id, body));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<GameMappingResult> delete(@PathVariable UUID id,
                                                    @RequestParam(defaultValue = "false") boolean applyLive) {
        AdminAccess.require(filler);
        return answer(apiService.adminDeleteGameMapping(id, applyLive));
    }

    /** The games detected on a platform, for the form's suggestions. */
    @GetMapping("/detected")
    public ResponseEntity<List<DetectedGameDto>> detected(@RequestParam String sourceType,
                                                          @RequestParam(defaultValue = "") String q) {
        AdminAccess.require(filler);
        return answer(apiService.adminDetectedGames(sourceType, q));
    }

    /** The Twitch categories matching {@code q}, as {@code [{id, name}]}. */
    @GetMapping("/categories")
    public ResponseEntity<List<TwitchCategoryDto>> categories(@RequestParam(defaultValue = "") String q) {
        AdminAccess.require(filler);
        return answer(apiService.adminSearchCategories(q));
    }

    private static <T> ResponseEntity<T> answer(ApiClient.Result<T> result) {
        return ResponseEntity.status(result.status()).body(result.body());
    }

    /** 502 when catapult-api failed (a null list). */
    private static <T> ResponseEntity<T> answer(T body) {
        return body == null ? ResponseEntity.status(502).build() : ResponseEntity.ok(body);
    }
}
