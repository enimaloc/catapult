package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.account.ApiTokenCreatedDto;
import fr.enimaloc.catapult.common.dto.account.ApiTokenStatusDto;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Browser-facing relay of the logged-in member's read-only personal API token to catapult-api (the session JWT never
 * reaches the browser), the same verbs as the full token's (ChannelCustomBindingsController). Open to every member:
 * catapult-api reads the member from the JWT, not from a path.
 */
@RestController
@RequiredArgsConstructor
public class ChannelReadTokenController {
    private final ApiService apiService;

    /**
     * A read-only token just generated, shown once, and the chat bots' summary link built on it
     * ({@link ModelFiller#summaryUrl}), for the page to put in place of the token's placeholder.
     */
    public record ReadApiTokenCreated(String token, Instant createdAt, String summaryUrl) {}

    @GetMapping("/me/api-token/read")
    public ResponseEntity<ApiTokenStatusDto> status() {
        ApiTokenStatusDto status = apiService.readApiTokenStatus();
        return status == null ? ResponseEntity.status(HttpStatus.BAD_GATEWAY).build() : ResponseEntity.ok(status);
    }

    /** The new token, shown once: not cacheable, and relayed as is (never logged or kept here). */
    @PostMapping("/me/api-token/read")
    public ResponseEntity<ReadApiTokenCreated> regenerate() {
        ApiClient.Result<ApiTokenCreatedDto> result = apiService.regenerateReadApiToken();
        int status = result.status() >= 200 && result.status() < 600 ? result.status() : HttpStatus.BAD_GATEWAY.value();
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status).cacheControl(CacheControl.noStore());
        if (!result.ok() || result.body() == null) return response.build();
        ApiTokenCreatedDto created = result.body();
        return response.body(new ReadApiTokenCreated(created.token(), created.createdAt(),
                ModelFiller.summaryUrl(created.token())));
    }

    @PostMapping("/me/api-token/read/delete")
    public ResponseEntity<Void> revoke() {
        return ApiStatus.answer(apiService.revokeReadApiToken());
    }
}
