package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.admin.AdminProviderIgdbQueryRequest;
import fr.enimaloc.catapult.common.dto.ExplorerData;
import fr.enimaloc.catapult.common.dto.QueryResult;
import fr.enimaloc.catapult.service.provider.IgdbRawQueries;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;

@Slf4j
@RestController
@Profile("dev")
@RequestMapping(ApiPaths.INTERNAL + "/dev/igdb")
@RequiredArgsConstructor
public class ApiDevIgdbController {

    private static final List<String> ENDPOINTS = List.of(
            "age_ratings", "age_rating_content_descriptions", "artworks",
            "characters", "collections", "companies", "covers",
            "external_games", "external_game_sources", "franchises",
            "game_engines", "game_modes", "games", "genres",
            "involved_companies", "keywords", "language_supports", "languages",
            "multiplayer_modes", "platforms", "platform_families",
            "player_perspectives", "release_dates", "screenshots",
            "themes", "videos", "websites"
    );

    private final IgdbRawQueries queries;

    @GetMapping
    public ExplorerData endpoints() {
        return new ExplorerData(ENDPOINTS);
    }

    @PostMapping("/query")
    public QueryResult query(@RequestBody AdminProviderIgdbQueryRequest body) {
        if (!body.endpoint().matches("[a-z_]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid endpoint: " + body.endpoint());
        }

        return queries.explorerQuery(body);
    }
}
