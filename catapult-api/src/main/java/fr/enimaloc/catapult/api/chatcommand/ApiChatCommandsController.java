package fr.enimaloc.catapult.api.chatcommand;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.common.dto.chatcommand.EnabledChatCommandDto;
import fr.enimaloc.catapult.service.chatcommand.ChatCommandDefinitionService;
import fr.enimaloc.catapult.service.chatcommand.ChatCommandDefinitionService.CommandDto;
import fr.enimaloc.catapult.service.chatcommand.ChatCommandDefinitionService.ListResponse;
import fr.enimaloc.catapult.service.chatcommand.ChatCommandDefinitionService.UpsertRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST endpoints for managing chat command definitions owned by the current user.
 *
 * <p>All endpoints are gated by the {@code chat.commands} experiment.</p>
 */
@RestController
@RequestMapping(ApiPaths.USER + "/chat-commands")
@RequiredArgsConstructor
public class ApiChatCommandsController {

    private final ChatCommandDefinitionService definitions;
    private final ApiUserResolver userResolver;

    @PreAuthorize("@authz.isSelf(#userId)")
    @GetMapping
    public ListResponse list(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        return definitions.list(userResolver.viewerByTwitchId(jwt));
    }

    /**
     * The enabled commands, for the page moving them to another chat bot. Not behind the gate: it
     * is the way out of these commands, and stays open once the experiment is wound down.
     */
    @PreAuthorize("@authz.isSelf(#userId)")
    @GetMapping("/enabled")
    public List<EnabledChatCommandDto> enabled(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        return definitions.enabled(userResolver.viewerByTwitchId(jwt));
    }

    @PreAuthorize("@authz.isSelf(#userId)")
    @PostMapping("/presets/{presetKey}")
    public ResponseEntity<CommandDto> instantiatePreset(
        @PathVariable UUID userId,
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable String presetKey
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(definitions.instantiatePreset(userResolver.viewerByTwitchId(jwt), presetKey));
    }

    @PreAuthorize("@authz.isSelf(#userId)")
    @PostMapping
    public ResponseEntity<CommandDto> create(
        @PathVariable UUID userId,
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody UpsertRequest req
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(definitions.create(userResolver.viewerByTwitchId(jwt), req));
    }

    @PreAuthorize("@authz.isSelf(#userId)")
    @PutMapping("/{id}")
    public CommandDto update(
        @PathVariable UUID userId,
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id,
        @Valid @RequestBody UpsertRequest req
    ) {
        return definitions.update(userResolver.viewerByTwitchId(jwt), id, req);
    }

    @PreAuthorize("@authz.isSelf(#userId)")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
        @PathVariable UUID userId,
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id
    ) {
        definitions.delete(userResolver.viewerByTwitchId(jwt), id);
        return ResponseEntity.noContent().build();
    }
}
