package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.common.dto.admin.RiotAddKeyRequest;
import fr.enimaloc.catapult.common.dto.admin.RiotKeysPageData;
import fr.enimaloc.catapult.service.enrichment.riot.RiotPlatform.Cluster;
import fr.enimaloc.catapult.service.riot.RiotKeyPoolService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.regex.Pattern;

/** The shared Riot API keys ({@code RGAPI-<uuid>}), each optionally kept to one routing cluster. */
@RestController
@RequestMapping(ApiPaths.ADMIN + "/riot-keys")
@RequiredArgsConstructor
public class ApiAdminRiotKeysController {

    static final Pattern KEY_PATTERN =
            Pattern.compile("RGAPI-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final RiotKeyPoolService keys;

    @GetMapping
    public RiotKeysPageData page() {
        return keys.page();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void add(@RequestBody RiotAddKeyRequest body) {
        String trimmed = body == null || body.apiKey() == null ? "" : body.apiKey().trim();
        if (!KEY_PATTERN.matcher(trimmed).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Riot API key format");
        }
        String rawScope = body.regionScope();
        Optional<Cluster> scope = Optional.empty();
        if (rawScope != null && !rawScope.isBlank()) {
            scope = Optional.of(Cluster.parse(rawScope).orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Unknown Riot cluster (americas, asia, europe or sea)")));
        }
        keys.add(trimmed, scope);
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
