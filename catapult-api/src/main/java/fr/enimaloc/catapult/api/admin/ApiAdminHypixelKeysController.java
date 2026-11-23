package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.common.dto.admin.AddKeyRequest;
import fr.enimaloc.catapult.common.dto.admin.HypixelKeysPageData;
import fr.enimaloc.catapult.service.hypixel.HypixelKeyPoolService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.regex.Pattern;

/** The shared Hypixel API keys (a UUID, as the Hypixel Developer Dashboard gives them). */
@RestController
@RequestMapping(ApiPaths.ADMIN + "/hypixel-keys")
@RequiredArgsConstructor
public class ApiAdminHypixelKeysController {

    static final Pattern KEY_PATTERN =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final HypixelKeyPoolService keys;

    @GetMapping
    public HypixelKeysPageData page() {
        return keys.page();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void add(@RequestBody AddKeyRequest body) {
        String trimmed = body == null || body.apiKey() == null ? "" : body.apiKey().trim().toLowerCase(Locale.ROOT);
        if (!KEY_PATTERN.matcher(trimmed).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Hypixel API key format");
        }
        keys.add(trimmed);
    }

    @DeleteMapping("/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String keyId) {
        keys.delete(keyId);
    }

    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void refresh() {
        keys.refresh();
    }
}
