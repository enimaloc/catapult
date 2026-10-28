package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.api.CustomBindingViews;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingsStateDto;
import fr.enimaloc.catapult.service.binding.CustomBindingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * The token owner's custom bindings, for a Stream Deck or a script: the list, and which one is active. Reading takes
 * either personal token, changing the active one the full token only (see ApiSecurityConfig); the principal is the
 * member's account id. A binding is named by its id or its name, whatever its case.
 */
@RestController
@RequestMapping(path = ApiV2.ME_CUSTOM_BINDINGS_PATH, version = ApiV2.VERSION)
@RequiredArgsConstructor
@Tag(name = "Me — custom bindings", description = "Trigger your channel's custom bindings, by your personal "
        + "API token (Authorization: Bearer cat_…) generated from the account settings; the read-only token "
        + "(catr_…) lists them only.")
public class MeCustomBindingsController {

    private final CustomBindingService customBindingService;
    private final CustomBindingViews views;
    private final ApiUserResolver userResolver;

    /** The binding to apply, by its id or its name (case ignored). */
    public record ActivateRequest(@Schema(description = "The custom binding's id or name, case ignored.",
            example = "Art", requiredMode = Schema.RequiredMode.REQUIRED) String idOrName) {}

    @GetMapping
    @Operation(summary = "The custom bindings and the active one")
    public CustomBindingsStateDto list(@AuthenticationPrincipal UUID userId) {
        return views.state(userResolver.tokenOwner(userId));
    }

    @PutMapping("/active")
    @Operation(summary = "Apply a custom binding now (204), live or not; 400 no idOrName, 404 unknown, 409 Twitch "
            + "link broken or Catapult paused, 502 Twitch failed; errors are application/problem+json with a code")
    public ResponseEntity<Void> activate(@AuthenticationPrincipal UUID userId,
                                         @RequestBody ActivateRequest request) {
        if (request.idOrName() == null || request.idOrName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "idOrName is required");
        }
        return customBindingService.activateByIdOrName(userResolver.tokenOwner(userId), request.idOrName())
                .map(CustomBindingViews::activation)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown custom binding"));
    }

    @DeleteMapping("/active")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "End the active custom binding, if any: the game open now takes over")
    public void deactivate(@AuthenticationPrincipal UUID userId) {
        customBindingService.deactivate(userResolver.tokenOwner(userId));
    }
}
