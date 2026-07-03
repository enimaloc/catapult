package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.service.IgdbService;
import fr.enimaloc.catapult.service.IgdbService.IgdbGame;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin-only IGDB game search, used by the members admin page to pick a game
 * for the mock Steam integration. Secured by {@code /api/admin/**} → ROLE_ADMIN.
 */
@RestController
@RequestMapping("/api/admin/igdb")
public class ApiAdminIgdbController {

    private final IgdbService igdbService;

    public ApiAdminIgdbController(IgdbService igdbService) {
        this.igdbService = igdbService;
    }

    @GetMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<IgdbGame> search(@RequestParam(defaultValue = "") String q) {
        if (q.isBlank()) return List.of();
        return igdbService.searchGames(q);
    }
}
