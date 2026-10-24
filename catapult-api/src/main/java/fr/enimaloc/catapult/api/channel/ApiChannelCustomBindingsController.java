package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.api.CustomBindingViews;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingDto;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingErrorDto;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingRequest;
import fr.enimaloc.catapult.service.binding.CustomBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingSaveResult;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingsStateDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.binding.CustomBindingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * The channel owner's custom bindings, from the logged-in site: only the owner (an admin through impersonation, like
 * every owner-only route) manages them; the experiment's 403 comes from {@link CustomBindingService}.
 */
@RestController
@RequestMapping("/api/channels/{username}/custom-bindings")
@RequiredArgsConstructor
public class ApiChannelCustomBindingsController {

    private final ApiUserResolver userResolver;
    private final CustomBindingService customBindingService;
    private final CustomBindingViews views;

    @GetMapping
    public CustomBindingsStateDto state(@PathVariable String username, @AuthenticationPrincipal Jwt jwt) {
        return views.state(userResolver.ownChannel(username, jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomBindingDto create(@PathVariable String username, @AuthenticationPrincipal Jwt jwt,
                                   @RequestBody CustomBindingRequest body) {
        UserAccount owner = userResolver.ownChannel(username, jwt);
        return CustomBindingViews.toDto(customBindingService.create(owner, body));
    }

    /** Always saved; when it is the active one, how re-applying it to Twitch went (null otherwise). */
    @PostMapping("/{id}")
    public CustomBindingSaveResult update(@PathVariable String username, @PathVariable UUID id,
                                          @AuthenticationPrincipal Jwt jwt, @RequestBody CustomBindingRequest body) {
        UserAccount owner = userResolver.ownChannel(username, jwt);
        return CustomBindingViews.saved(customBindingService.update(owner, id, body));
    }

    @PostMapping("/{id}/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String username, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        customBindingService.delete(userResolver.ownChannel(username, jwt), id);
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<?> activate(@PathVariable String username, @PathVariable UUID id,
                                      @AuthenticationPrincipal Jwt jwt) {
        UserAccount owner = userResolver.ownChannel(username, jwt);
        return customBindingService.resolve(owner, id.toString())
                .map(binding -> CustomBindingViews.activation(customBindingService.activate(owner, binding)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown custom binding"));
    }

    @PostMapping("/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable String username, @AuthenticationPrincipal Jwt jwt) {
        customBindingService.deactivate(userResolver.ownChannel(username, jwt));
    }

    /** A refused save tells why in a {@link CustomBindingErrorDto} (name taken, limit reached, invalid). */
    @ExceptionHandler(CustomBindingException.class)
    public ResponseEntity<CustomBindingErrorDto> refused(CustomBindingException e) {
        return ResponseEntity.status(e.getStatusCode()).body(new CustomBindingErrorDto(e.code(), e.getReason()));
    }
}
