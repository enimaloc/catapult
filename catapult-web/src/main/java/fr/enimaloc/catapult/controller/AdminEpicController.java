package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.ApiService.AdminCall;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * The Keys & limits tab's Epic bot pool: add a bot, or log one in again, with what the admin pasted from Epic's code
 * page, then enable / disable, reorder, lift "full" and delete, each answered with catapult-api's status. A refusal's
 * message ({@code {"error": ...}}) is relayed so the card can show it. The pasted text holds a one-time authorization
 * code: it only travels in the JSON body, and is never logged nor sent back.
 */
@RestController
@RequestMapping("/admin/epic-accounts")
@RequiredArgsConstructor
public class AdminEpicController {
    private final ApiService apiService;
    private final ModelFiller filler;

    public record CodeBody(String code, UUID accountId) {
        /** Never print the pasted code. */
        @Override
        public String toString() {
            return "CodeBody[]";
        }
    }

    public record EnabledBody(Boolean enabled) {}

    public record DetailsBody(Integer fillOrder) {}

    /** Adds a bot, or logs {@code accountId} in again, from the pasted code. */
    @PostMapping
    public ResponseEntity<?> addOrReauth(@RequestBody CodeBody body) {
        AdminAccess.require(filler);
        if (body.code() == null || body.code().isBlank()) return ResponseEntity.badRequest().build();
        return relay(apiService.adminAddEpicAccount(body.code().trim(), body.accountId()));
    }

    @PostMapping("/{id}/enabled")
    public ResponseEntity<?> enabled(@PathVariable UUID id, @RequestBody EnabledBody body) {
        AdminAccess.require(filler);
        if (body.enabled() == null) return ResponseEntity.badRequest().build();
        return relay(apiService.adminPatchEpicAccount(id, Map.of("enabled", body.enabled())));
    }

    @PostMapping("/{id}/reset-limit")
    public ResponseEntity<?> resetLimit(@PathVariable UUID id) {
        AdminAccess.require(filler);
        return relay(apiService.adminPatchEpicAccount(id, Map.of("friendLimitReached", false)));
    }

    @PostMapping("/{id}/details")
    public ResponseEntity<?> details(@PathVariable UUID id, @RequestBody DetailsBody body) {
        AdminAccess.require(filler);
        if (body.fillOrder() == null || body.fillOrder() < 0) return ResponseEntity.badRequest().build();
        return relay(apiService.adminPatchEpicAccount(id, Map.of("fillOrder", body.fillOrder())));
    }

    @PostMapping("/{id}/delete")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        AdminAccess.require(filler);
        return ResponseEntity.status(apiService.adminDeleteEpicAccount(id)).build();
    }

    /** catapult-api's status with its body on a 2xx, else with its refusal message as {@code {"error": ...}}. */
    private static ResponseEntity<?> relay(AdminCall<?> call) {
        if (call.ok()) {
            return call.body() == null ? ResponseEntity.status(call.status()).build()
                    : ResponseEntity.status(call.status()).body(call.body());
        }
        return call.error() == null ? ResponseEntity.status(call.status()).build()
                : ResponseEntity.status(call.status()).body(Map.of("error", call.error()));
    }
}
