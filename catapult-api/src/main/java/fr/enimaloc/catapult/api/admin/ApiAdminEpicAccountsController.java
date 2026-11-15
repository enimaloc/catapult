package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.common.dto.admin.EpicAccountDto;
import fr.enimaloc.catapult.common.dto.admin.EpicAccountsDto;
import fr.enimaloc.catapult.service.epic.EpicServiceAccountAdmin;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.ADMIN + "/epic-accounts")
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("epic.enabled")
public class ApiAdminEpicAccountsController {

    private final EpicServiceAccountAdmin accounts;

    /** The pool, with the page that gives a bot's authorization code. */
    @GetMapping
    public EpicAccountsDto list() {
        return new EpicAccountsDto(accounts.list(), EpicServiceAccountAdmin.CODE_URL);
    }

    /**
     * Adds a bot, or logs one in again, from what the admin pasted ({@code code}); the optional {@code accountId}
     * makes it the re-authentication of that bot. 400 with {@code {"error": message}} when refused.
     */
    @PostMapping
    public EpicAccountDto addOrReauth(@RequestBody Map<String, String> body) {
        String accountId = body.get("accountId");
        UUID reauth;
        try {
            reauth = accountId == null || accountId.isBlank() ? null : UUID.fromString(accountId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("accountId is not a valid id");
        }
        return accounts.addOrReauth(body.get("code"), reauth);
    }

    @PatchMapping("/{id}")
    public EpicAccountDto update(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return accounts.update(id, body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        accounts.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** A refused code: the message is written for the admin, who reads it as is. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> refused(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
