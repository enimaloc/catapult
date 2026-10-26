package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.api.CustomBindingViews;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingDto;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingRequest;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingSaveResult;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingsStateDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.binding.CustomBindingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * The channel owner's custom bindings, from the logged-in site: only the owner (an admin through impersonation, like
 * every owner-only route) manages them ({@code @authz.ownsChannel}); the experiment's 403 comes from
 * {@link CustomBindingService}. The rule sits on each handler, not on the class.
 */
@RestController
@RequestMapping(ApiPaths.USER + "/custom-bindings")
@RequiredArgsConstructor
public class ApiChannelCustomBindingsController {

    private final ApiUserResolver userResolver;
    private final CustomBindingService customBindingService;
    private final CustomBindingViews views;

    @PreAuthorize("@authz.ownsChannel(#userId)")
    @GetMapping
    public CustomBindingsStateDto state(@PathVariable UUID userId) {
        return views.state(userResolver.user(userId));
    }

    @PreAuthorize("@authz.ownsChannel(#userId)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomBindingDto create(@PathVariable UUID userId, @RequestBody CustomBindingRequest body) {
        UserAccount owner = userResolver.user(userId);
        return CustomBindingViews.toDto(customBindingService.create(owner, body));
    }

    /** Always saved; when it is the active one, how re-applying it to Twitch went (null otherwise). */
    @PreAuthorize("@authz.ownsChannel(#userId)")
    @PutMapping("/{customBindingId}")
    public CustomBindingSaveResult update(@PathVariable UUID userId, @PathVariable UUID customBindingId,
                                          @RequestBody CustomBindingRequest body) {
        UserAccount owner = userResolver.user(userId);
        return CustomBindingViews.saved(customBindingService.update(owner, customBindingId, body));
    }

    @PreAuthorize("@authz.ownsChannel(#userId)")
    @DeleteMapping("/{customBindingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID userId, @PathVariable UUID customBindingId) {
        customBindingService.delete(userResolver.user(userId), customBindingId);
    }

    @PreAuthorize("@authz.ownsChannel(#userId)")
    @PostMapping("/{customBindingId}/activate")
    public ResponseEntity<Void> activate(@PathVariable UUID userId, @PathVariable UUID customBindingId) {
        return customBindingService.activateByIdOrName(userResolver.user(userId), customBindingId.toString())
                .map(CustomBindingViews::activation)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown custom binding"));
    }

    @PreAuthorize("@authz.ownsChannel(#userId)")
    @PostMapping("/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable UUID userId) {
        customBindingService.deactivate(userResolver.user(userId));
    }
}
