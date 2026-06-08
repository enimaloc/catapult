package fr.enimaloc.catapult.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.service.IgdbService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;

@Slf4j
@RestController
@Profile("dev")
@RequestMapping("/api/dev/igdb")
@RequiredArgsConstructor
public class ApiDevIgdbController {

    private static final String IGDB_API_BASE = "https://api.igdb.com/v4/";

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

    @Value("${app.igdb.client-id:}")
    private String clientId;

    private final IgdbService igdbService;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @GetMapping
    public ExplorerData endpoints() {
        return new ExplorerData(ENDPOINTS);
    }

    @PostMapping("/query")
    public QueryResult query(@RequestBody QueryRequest body) {
        if (!body.endpoint().matches("[a-z_]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid endpoint: " + body.endpoint());
        }

        String token = igdbService.getAppToken();
        if (token.isBlank()) {
            return new QueryResult(null, "IGDB token not available — vérifiez la configuration client-id/secret");
        }

        try {
            String json = restClient.post()
                    .uri(IGDB_API_BASE + body.endpoint())
                    .header("Client-ID", clientId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(body.query())
                    .retrieve()
                    .body(String.class);

            String pretty = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(objectMapper.readTree(json));
            return new QueryResult(pretty, null);
        } catch (HttpClientErrorException e) {
            log.warn("[DevIgdbExplorer] HTTP error on {}: {}", body.endpoint(), e.getStatusCode());
            return new QueryResult(null, e.getStatusCode() + "\n" + e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("[DevIgdbExplorer] Query failed on {}: {}", body.endpoint(), e.getMessage());
            return new QueryResult(null, e.getMessage());
        }
    }

    public record ExplorerData(List<String> endpoints) {}

    public record QueryRequest(String endpoint, String query) {}

    public record QueryResult(String result, String error) {
        public boolean hasError() { return error != null; }
    }
}
