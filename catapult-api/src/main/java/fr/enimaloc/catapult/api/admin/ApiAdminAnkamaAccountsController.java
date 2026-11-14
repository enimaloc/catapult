package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.common.dto.admin.AnkamaAccountDto;
import fr.enimaloc.catapult.common.dto.admin.AnkamaLoginStartDto;
import fr.enimaloc.catapult.service.ankama.AnkamaServiceAccountAdmin;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.ADMIN + "/ankama-accounts")
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("ankama.enabled")
public class ApiAdminAnkamaAccountsController {

    private final AnkamaServiceAccountAdmin accounts;

    @GetMapping
    public List<AnkamaAccountDto> list() {
        return accounts.list();
    }

    /** Starts a login; the optional {@code accountId} makes it the re-authentication of that bot. */
    @PostMapping("/login")
    public AnkamaLoginStartDto startLogin(@RequestBody(required = false) Map<String, String> body) {
        String accountId = body == null ? null : body.get("accountId");
        UUID reauth;
        try {
            reauth = accountId == null || accountId.isBlank() ? null : UUID.fromString(accountId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("accountId is not a valid id");
        }
        return accounts.startLogin(reauth);
    }

    /** Finishes a login with the redirect URL the admin pasted; 400 with {@code {"error": message}} when refused. */
    @PostMapping("/login/{loginId}/complete")
    public AnkamaAccountDto completeLogin(@PathVariable UUID loginId, @RequestBody Map<String, String> body) {
        return accounts.completeLogin(loginId, body.get("redirectUrl"));
    }

    /** The Shield code of a bot waiting for one; 400 with Ankama's message when it is wrong. */
    @PostMapping("/{id}/code")
    public ResponseEntity<Void> submitCode(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        accounts.submitShieldCode(id, body.get("code"));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{id}/code/resend")
    public ResponseEntity<Void> resendCode(@PathVariable UUID id) {
        accounts.resendShieldCode(id);
        return ResponseEntity.accepted().build();
    }

    @PatchMapping("/{id}")
    public AnkamaAccountDto update(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return accounts.update(id, body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        accounts.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** A refused login or code: the message is written for the admin, who reads it as is. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> refused(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
