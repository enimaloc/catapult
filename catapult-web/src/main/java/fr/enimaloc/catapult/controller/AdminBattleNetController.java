package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.service.ApiService;
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
 * The admin Keys tab's Battle.net bot pool: add a bot, submit its 2FA code, re-authenticate, enable /
 * disable, rename and reorder, lift "full", change its password and delete, each answered with catapult-api's status
 * (409 while a login is running or none waits for a code, 404 unknown, 502 when unreachable). The
 * credentials and the code only travel in the JSON bodies, and are never logged nor sent back.
 */
@RestController
@RequestMapping("/admin/battlenet-accounts")
@RequiredArgsConstructor
public class AdminBattleNetController {
    private final ApiService apiService;
    private final ModelFiller filler;

    public record CreateBody(String label, String email, String password) {
        /** Never print the credentials. */
        @Override
        public String toString() {
            return "CreateBody[label=" + label + "]";
        }
    }

    public record CodeBody(String code) {
        @Override
        public String toString() {
            return "CodeBody[]";
        }
    }

    public record EnabledBody(Boolean enabled) {}

    public record DetailsBody(String label, Integer fillOrder) {}

    public record PasswordBody(String password) {
        @Override
        public String toString() {
            return "PasswordBody[]";
        }
    }

    @PostMapping
    public ResponseEntity<Void> create(@RequestBody CreateBody body) {
        AdminAccess.require(filler);
        if (blank(body.label()) || blank(body.email()) || blank(body.password())) return ResponseEntity.badRequest().build();
        return ResponseEntity.status(apiService.adminCreateBattleNetAccount(
                body.label().trim(), body.email().trim(), body.password())).build();
    }

    @PostMapping("/{id}/code")
    public ResponseEntity<Void> code(@PathVariable UUID id, @RequestBody CodeBody body) {
        AdminAccess.require(filler);
        if (blank(body.code())) return ResponseEntity.badRequest().build();
        return ResponseEntity.status(apiService.adminSubmitBattleNetCode(id, body.code().trim())).build();
    }

    @PostMapping("/{id}/reauth")
    public ResponseEntity<Void> reauth(@PathVariable UUID id) {
        AdminAccess.require(filler);
        return ResponseEntity.status(apiService.adminReauthBattleNetAccount(id)).build();
    }

    @PostMapping("/{id}/enabled")
    public ResponseEntity<Void> enabled(@PathVariable UUID id, @RequestBody EnabledBody body) {
        AdminAccess.require(filler);
        if (body.enabled() == null) return ResponseEntity.badRequest().build();
        return ResponseEntity.status(apiService.adminPatchBattleNetAccount(id, Map.of("enabled", body.enabled()))).build();
    }

    @PostMapping("/{id}/reset-limit")
    public ResponseEntity<Void> resetLimit(@PathVariable UUID id) {
        AdminAccess.require(filler);
        return ResponseEntity.status(apiService.adminPatchBattleNetAccount(id, Map.of("friendLimitReached", false))).build();
    }

    @PostMapping("/{id}/details")
    public ResponseEntity<Void> details(@PathVariable UUID id, @RequestBody DetailsBody body) {
        AdminAccess.require(filler);
        if (blank(body.label()) || body.fillOrder() == null || body.fillOrder() < 0) return ResponseEntity.badRequest().build();
        return ResponseEntity.status(apiService.adminPatchBattleNetAccount(id,
                Map.of("label", body.label().trim(), "fillOrder", body.fillOrder()))).build();
    }

    @PostMapping("/{id}/password")
    public ResponseEntity<Void> password(@PathVariable UUID id, @RequestBody PasswordBody body) {
        AdminAccess.require(filler);
        if (blank(body.password())) return ResponseEntity.badRequest().build();
        return ResponseEntity.status(apiService.adminPatchBattleNetAccount(id, Map.of("password", body.password()))).build();
    }

    @PostMapping("/{id}/delete")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        AdminAccess.require(filler);
        return ResponseEntity.status(apiService.adminDeleteBattleNetAccount(id)).build();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
