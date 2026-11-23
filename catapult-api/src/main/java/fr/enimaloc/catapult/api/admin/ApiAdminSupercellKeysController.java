package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.common.dto.admin.AddKeyRequest;
import fr.enimaloc.catapult.common.dto.admin.SupercellKeysPageData;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellGame;
import fr.enimaloc.catapult.service.supercell.SupercellKeyPoolService;
import fr.enimaloc.catapult.service.supercell.SupercellKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * The shared Supercell API keys, one pool per game ({@code clash-of-clans}, {@code clash-royale},
 * {@code brawl-stars}): each game has its own developer portal, whose keys are JWTs bound to IP addresses. A key whose
 * JWT names other scopes than the game's is refused (a Clash Royale key in the Clash of Clans pool would never work).
 */
@RestController
@RequestMapping(ApiPaths.ADMIN + "/supercell-keys/{game}")
@RequiredArgsConstructor
public class ApiAdminSupercellKeysController {

    private final SupercellKeyPoolService keys;

    private static SupercellGame game(String game) {
        return SupercellGame.parse(game).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Unknown Supercell game (clash-of-clans, clash-royale or brawl-stars)"));
    }

    @GetMapping
    public SupercellKeysPageData page(@PathVariable String game) {
        return keys.page(game(game));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void add(@PathVariable String game, @RequestBody AddKeyRequest body) {
        SupercellGame on = game(game);
        String trimmed = body == null || body.apiKey() == null ? "" : body.apiKey().strip();
        if (!SupercellKeys.wellFormed(trimmed)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Supercell API key format");
        }
        List<String> scopes = SupercellKeys.claims(trimmed).map(SupercellKeys.Claims::scopes).orElse(List.of());
        if (!scopes.isEmpty() && !scopes.contains(on.scope())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This key is not a " + on.title() + " key");
        }
        keys.add(on, trimmed);
    }

    @DeleteMapping("/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String game, @PathVariable String keyId) {
        keys.delete(game(game), keyId);
    }

    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void refresh(@PathVariable String game) {
        keys.refresh(game(game));
    }
}
