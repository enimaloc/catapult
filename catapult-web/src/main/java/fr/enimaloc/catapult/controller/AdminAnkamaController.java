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
 * The admin Ankama tab's bot pool: start a login (a new bot, or a re-authentication), finish it with the address the
 * admin pasted, submit or resend the Shield code, enable / disable, reorder, lift "full" and delete, each answered
 * with catapult-api's status. A refusal's message ({@code {"error": ...}}, Ankama's own text for a wrong code) is
 * relayed so the tab can show it. The pasted address holds a one-time code: it only travels in the JSON body, and is
 * never logged nor sent back.
 */
@RestController
@RequestMapping("/admin/ankama-accounts")
@RequiredArgsConstructor
public class AdminAnkamaController {
    private final ApiService apiService;
    private final ModelFiller filler;

    public record LoginBody(UUID accountId) {}

    public record CompleteBody(String redirectUrl) {
        /** Never print the pasted address. */
        @Override
        public String toString() {
            return "CompleteBody[]";
        }
    }

    public record CodeBody(String code) {
        @Override
        public String toString() {
            return "CodeBody[]";
        }
    }

    public record EnabledBody(Boolean enabled) {}

    public record DetailsBody(Integer fillOrder) {}

    @PostMapping("/login")
    public ResponseEntity<?> startLogin(@RequestBody LoginBody body) {
        AdminAccess.require(filler);
        return relay(apiService.adminStartAnkamaLogin(body.accountId()));
    }

    @PostMapping("/login/{loginId}/complete")
    public ResponseEntity<?> completeLogin(@PathVariable UUID loginId, @RequestBody CompleteBody body) {
        AdminAccess.require(filler);
        if (blank(body.redirectUrl())) return ResponseEntity.badRequest().build();
        return relay(apiService.adminCompleteAnkamaLogin(loginId, body.redirectUrl().trim()));
    }

    @PostMapping("/{id}/code")
    public ResponseEntity<?> code(@PathVariable UUID id, @RequestBody CodeBody body) {
        AdminAccess.require(filler);
        if (blank(body.code())) return ResponseEntity.badRequest().build();
        return relay(apiService.adminSubmitAnkamaCode(id, body.code().trim()));
    }

    @PostMapping("/{id}/code/resend")
    public ResponseEntity<Void> resend(@PathVariable UUID id) {
        AdminAccess.require(filler);
        return ResponseEntity.status(apiService.adminResendAnkamaCode(id)).build();
    }

    @PostMapping("/{id}/enabled")
    public ResponseEntity<?> enabled(@PathVariable UUID id, @RequestBody EnabledBody body) {
        AdminAccess.require(filler);
        if (body.enabled() == null) return ResponseEntity.badRequest().build();
        return relay(apiService.adminPatchAnkamaAccount(id, Map.of("enabled", body.enabled())));
    }

    @PostMapping("/{id}/reset-limit")
    public ResponseEntity<?> resetLimit(@PathVariable UUID id) {
        AdminAccess.require(filler);
        return relay(apiService.adminPatchAnkamaAccount(id, Map.of("friendLimitReached", false)));
    }

    @PostMapping("/{id}/details")
    public ResponseEntity<?> details(@PathVariable UUID id, @RequestBody DetailsBody body) {
        AdminAccess.require(filler);
        if (body.fillOrder() == null || body.fillOrder() < 0) return ResponseEntity.badRequest().build();
        return relay(apiService.adminPatchAnkamaAccount(id, Map.of("fillOrder", body.fillOrder())));
    }

    @PostMapping("/{id}/delete")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        AdminAccess.require(filler);
        return ResponseEntity.status(apiService.adminDeleteAnkamaAccount(id)).build();
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

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
