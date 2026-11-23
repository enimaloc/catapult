package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.common.dto.admin.AddKeyRequest;
import fr.enimaloc.catapult.common.dto.admin.OpenCriticKeysPageData;
import fr.enimaloc.catapult.service.opencritic.OpenCriticKeyPoolService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.regex.Pattern;

@RestController
@RequestMapping(ApiPaths.ADMIN + "/opencritic-keys")
@RequiredArgsConstructor
public class ApiAdminOpenCriticKeysController {

    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9]{40,60}");

    private final OpenCriticKeyPoolService keys;

    @GetMapping
    public OpenCriticKeysPageData page() {
        return keys.page();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void add(@RequestBody AddKeyRequest body) {
        String trimmed = body.apiKey().trim();
        if (!KEY_PATTERN.matcher(trimmed).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid OpenCritic API key format");
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
