package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.common.dto.admin.AddKeyRequest;
import fr.enimaloc.catapult.common.dto.admin.SteamGridDbKeysPageData;
import fr.enimaloc.catapult.service.steamgriddb.SteamGridDbKeyPoolService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.regex.Pattern;

@RestController
@RequestMapping(ApiPaths.ADMIN + "/steamgriddb-keys")
@RequiredArgsConstructor
public class ApiAdminSteamGridDbKeysController {

    private static final Pattern KEY_PATTERN = Pattern.compile("[0-9A-Fa-f]{32}");

    private final SteamGridDbKeyPoolService keys;

    @GetMapping
    public SteamGridDbKeysPageData page() {
        return keys.page();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void add(@RequestBody AddKeyRequest body) {
        String trimmed = body.apiKey().trim();
        if (!KEY_PATTERN.matcher(trimmed).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid SteamGridDB API key format");
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
