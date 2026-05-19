package fr.enimaloc.catapult.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.service.IgdbService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.HtmlUtils;

import java.util.List;

@Slf4j
@Controller
@Profile("dev")
@RequiredArgsConstructor
@RequestMapping("/dev/igdb")
public class DevIgdbExplorerController {

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
    public String explorer(Model model) {
        model.addAttribute("endpoints", ENDPOINTS);
        return "dev/igdb-explorer";
    }

    @PostMapping(value = "/query", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String executeQuery(
            @RequestParam String endpoint,
            @RequestParam String query) {

        if (!endpoint.matches("[a-z_]+")) {
            return errorFragment("Endpoint invalide: " + HtmlUtils.htmlEscape(endpoint));
        }

        String token = igdbService.getAppToken();
        if (token.isBlank()) {
            return errorFragment("Token IGDB non disponible — vérifiez la configuration client-id/secret");
        }

        try {
            String json = restClient.post()
                .uri(IGDB_API_BASE + endpoint)
                .header("Client-ID", clientId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.TEXT_PLAIN)
                .body(query)
                .retrieve()
                .body(String.class);

            String pretty = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(objectMapper.readTree(json));
            return resultFragment(pretty);
        } catch (HttpClientErrorException e) {
            log.warn("[DevIgdbExplorer] HTTP error on {}: {}", endpoint, e.getStatusCode());
            return errorFragment(e.getStatusCode() + "\n" + e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("[DevIgdbExplorer] Query failed on {}: {}", endpoint, e.getMessage());
            return errorFragment(e.getMessage());
        }
    }

    private String resultFragment(String prettyJson) {
        return "<pre class=\"igdb-result igdb-result--ok\">"
            + HtmlUtils.htmlEscape(prettyJson) + "</pre>";
    }

    private String errorFragment(String message) {
        return "<pre class=\"igdb-result igdb-result--error\">"
            + HtmlUtils.htmlEscape(message) + "</pre>";
    }
}
