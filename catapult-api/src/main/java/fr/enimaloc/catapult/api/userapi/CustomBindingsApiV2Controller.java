package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.api.CustomBindingViews;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingsStateDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.security.ApiSecurityConfig;
import fr.enimaloc.catapult.service.binding.CustomBindingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * The streamer's custom bindings, for a Stream Deck or a script: authenticated by the personal API token only
 * ({@code Authorization: Bearer cat_…}, see ApiSecurityConfig), the principal being the member's account id. A
 * binding is named by its id or its name, whatever its case ({@code …/Art/activate}).
 */
@RestController
@RequestMapping(ApiSecurityConfig.CUSTOM_BINDINGS_API)
@RequiredArgsConstructor
@Tag(name = "Custom bindings", description = "Trigger the channel's custom bindings, authenticated by the "
        + "personal API token (Authorization: Bearer cat_…) generated from the account settings.")
public class CustomBindingsApiV2Controller {

    private final CustomBindingService customBindingService;
    private final CustomBindingViews views;
    private final UserAccountRepository userAccountRepository;

    @GetMapping
    @Operation(summary = "The custom bindings and the active one")
    public CustomBindingsStateDto list(@AuthenticationPrincipal UUID userId) {
        return views.state(member(userId));
    }

    @PostMapping("/{idOrName}/activate")
    @Operation(summary = "Apply a custom binding now (204), live or not; 404 unknown, 409 Twitch link broken or "
            + "Catapult paused, 502 Twitch failed")
    public ResponseEntity<?> activate(@AuthenticationPrincipal UUID userId, @PathVariable String idOrName) {
        UserAccount user = member(userId);
        return customBindingService.resolve(user, idOrName)
                .map(binding -> CustomBindingViews.activation(customBindingService.activate(user, binding)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown custom binding"));
    }

    @PostMapping("/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "End the active custom binding, if any: the game open now takes over")
    public void deactivate(@AuthenticationPrincipal UUID userId) {
        customBindingService.deactivate(member(userId));
    }

    private UserAccount member(UUID userId) {
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return userAccountRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
}
