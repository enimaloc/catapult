package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.ApiKeyProvider;
import fr.enimaloc.catapult.service.ApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.function.IntSupplier;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * The admin Keys tab's Steam and DTDD actions (add, delete, refresh), forwarded to catapult-api
 * with the session's JWT and answered with its status (204; 400 for an invalid key format; 404 for
 * an unknown key; 502 when unreachable). {@code provider} is {@code steam} or {@code dtdd}, else
 * 404. The key (a secret) and the key id travel in the body and are never logged.
 */
@RestController
@RequestMapping("/admin/keys/{provider}")
@RequiredArgsConstructor
public class AdminKeysController {
    private final ApiService apiService;
    private final ModelFiller filler;

    public record AddBody(String apiKey) {}
    public record DeleteBody(String keyId) {}

    @PostMapping("/add")
    public ResponseEntity<Void> add(@PathVariable String provider, @RequestBody AddBody body) {
        ApiKeyProvider keys = authorized(provider);
        String key = body.apiKey() == null ? "" : body.apiKey().trim();
        if (key.isEmpty()) return ResponseEntity.badRequest().build();
        return relay(() -> apiService.adminAddApiKey(keys, key));
    }

    @PostMapping("/delete")
    public ResponseEntity<Void> delete(@PathVariable String provider, @RequestBody DeleteBody body) {
        ApiKeyProvider keys = authorized(provider);
        if (body.keyId() == null || body.keyId().isBlank()) return ResponseEntity.badRequest().build();
        return relay(() -> apiService.adminDeleteApiKey(keys, body.keyId()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<Void> refresh(@PathVariable String provider) {
        ApiKeyProvider keys = authorized(provider);
        return relay(() -> apiService.adminRefreshApiKeys(keys));
    }

    /** Only an admin gets to know which providers exist: 401 / 403 first, then 404 for an unknown one. */
    private ApiKeyProvider authorized(String provider) {
        AdminAccess.require(filler);
        ApiKeyProvider keys = ApiKeyProvider.of(provider);
        if (keys == null) throw new ResponseStatusException(NOT_FOUND);
        return keys;
    }

    private static ResponseEntity<Void> relay(IntSupplier action) {
        return ResponseEntity.status(action.getAsInt()).build();
    }
}
