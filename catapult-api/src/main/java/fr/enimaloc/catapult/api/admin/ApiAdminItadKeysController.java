package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.common.dto.admin.AddKeyRequest;
import fr.enimaloc.catapult.common.dto.admin.ItadKeysPageData;
import fr.enimaloc.catapult.service.itad.ItadKeyPoolService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.regex.Pattern;

@RestController
@RequestMapping(ApiPaths.ADMIN + "/itad-keys")
@RequiredArgsConstructor
public class ApiAdminItadKeysController {

    private static final Pattern KEY_PATTERN = Pattern.compile("[0-9A-Fa-f]{40}");

    private final ItadKeyPoolService keys;

    @GetMapping
    public ItadKeysPageData page() {
        return keys.page();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void add(@RequestBody AddKeyRequest body) {
        String trimmed = body.apiKey().trim();
        if (!KEY_PATTERN.matcher(trimmed).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid IsThereAnyDeal API key format");
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
