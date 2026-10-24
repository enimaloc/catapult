package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.account.ApiTokenCreatedDto;
import fr.enimaloc.catapult.common.dto.account.ApiTokenStatusDto;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingDto;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingErrorDto;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingRequest;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingSaveResult;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingsStateDto;
import fr.enimaloc.catapult.service.ActivationOutcome;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.CustomBindingSave;
import fr.enimaloc.catapult.service.http.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Browser-facing relay of the channel owner's custom bindings and of the member's personal API token to
 * catapult-api (the session JWT never reaches the browser). Same verbs as catapult-api's routes; each answers
 * catapult-api's own outcome, 502 when unreachable, so the page can tell a refusal (403 experiment closed, 404
 * unknown, 409 name taken or limit reached) from a failure.
 */
@RestController
@RequiredArgsConstructor
public class ChannelCustomBindingsController {
    private final ApiService apiService;

    @GetMapping("/channel/{username}/custom-bindings")
    public ResponseEntity<CustomBindingsStateDto> state(@PathVariable String username) {
        return answer(apiService.customBindings(username));
    }

    @PostMapping("/channel/{username}/custom-bindings")
    public ResponseEntity<?> create(@PathVariable String username, @RequestBody CustomBindingRequest body) {
        return relay(apiService.createCustomBinding(username, body));
    }

    /** The saved binding, and how re-applying it went when it is the active one. */
    @PostMapping("/channel/{username}/custom-bindings/{id}")
    public ResponseEntity<?> update(@PathVariable String username, @PathVariable UUID id,
                                                          @RequestBody CustomBindingRequest body) {
        return relay(apiService.updateCustomBinding(username, id, body));
    }

    @PostMapping("/channel/{username}/custom-bindings/{id}/delete")
    public ResponseEntity<Void> delete(@PathVariable String username, @PathVariable UUID id) {
        return ApiStatus.answer(apiService.deleteCustomBinding(username, id));
    }

    /** 204 once applied; 409 with the {@code code} to translate (no Twitch link, bot paused); 502 when Twitch failed. */
    @PostMapping("/channel/{username}/custom-bindings/{id}/activate")
    public ResponseEntity<CustomBindingErrorDto> activate(@PathVariable String username, @PathVariable UUID id) {
        ActivationOutcome outcome = apiService.activateCustomBinding(username, id);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(outcome.status());
        return outcome.code() == null ? response.build() : response.body(new CustomBindingErrorDto(outcome.code(), null));
    }

    @PostMapping("/channel/{username}/custom-bindings/deactivate")
    public ResponseEntity<Void> deactivate(@PathVariable String username) {
        return ApiStatus.answer(apiService.deactivateCustomBinding(username));
    }

    // The personal API token is the logged-in member's own (catapult-api reads it from the JWT, not from a path).

    @GetMapping("/me/api-token")
    public ResponseEntity<ApiTokenStatusDto> apiTokenStatus() {
        return answer(apiService.apiTokenStatus());
    }

    /** The new token, shown once: not cacheable, and relayed as is (never logged or kept here). */
    @PostMapping("/me/api-token")
    public ResponseEntity<ApiTokenCreatedDto> regenerateApiToken() {
        ApiClient.Result<ApiTokenCreatedDto> result = apiService.regenerateApiToken();
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status(result.status())).cacheControl(CacheControl.noStore());
        return result.ok() && result.body() != null ? response.body(result.body()) : response.build();
    }

    @PostMapping("/me/api-token/delete")
    public ResponseEntity<Void> revokeApiToken() {
        return ApiStatus.answer(apiService.revokeApiToken());
    }

    private static <T> ResponseEntity<T> answer(T body) {
        return body == null ? ResponseEntity.status(HttpStatus.BAD_GATEWAY).build() : ResponseEntity.ok(body);
    }

    /** catapult-api's status, with its body when it took the save, else the {@code code} to translate when it told one. */
    private static ResponseEntity<?> relay(CustomBindingSave<?> result) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status(result.status()));
        if (result.ok()) return result.body() != null ? response.body(result.body()) : response.build();
        return result.code() == null ? response.build() : response.body(new CustomBindingErrorDto(result.code(), null));
    }

    private static int status(int status) {
        return status >= 200 && status < 600 ? status : HttpStatus.BAD_GATEWAY.value();
    }
}
