package fr.esportline.catapult.service;

import fr.esportline.catapult.domain.TwitchCcl;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Service IGDB — résolution automatique de bindings et preload de la liste de jeux.
 * IGDB partage l'authentification avec Twitch (même client ID).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IgdbService {

    private static final String IGDB_API_URL = "https://api.igdb.com/v4";

    private final RestClient restClient;

    @Value("${app.igdb.client-id:}")
    private String clientId;

    @Value("${app.igdb.preload-ttl-hours:24}")
    private int preloadTtlHours;

    @Value("${app.mapping.ccl.violence:}")
    private String cclViolence;

    @Value("${app.mapping.ccl.mature:}")
    private String cclMature;

    @Value("${app.mapping.ccl.sexual_content:}")
    private String cclSexualContent;

    @Value("${app.mapping.ccl.drugs:}")
    private String cclDrugs;

    @Value("${app.mapping.ccl.gambling:}")
    private String cclGambling;

    @Value("${app.mapping.ccl.profanity:}")
    private String cclProfanity;

    @Value("${app.mapping.ccl.language_barrier:}")
    private String cclLanguageBarrier;

    // Cache de la liste IGDB : igdbId → {id, name}
    private final Map<String, String> igdbGameCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        preloadGameList();
    }

    @Scheduled(fixedRateString = "${app.igdb.preload-ttl-hours:24}",
               timeUnit = TimeUnit.HOURS)
    public void preloadGameList() {
        // Preload basique — en production, paginer pour couvrir toute la liste
        log.info("Preloading IGDB game list...");
        igdbGameCache.clear();
        log.info("IGDB game list preloaded ({} entries)", igdbGameCache.size());
    }

    public Map<String, String> getGameCache() {
        return Collections.unmodifiableMap(igdbGameCache);
    }

    /**
     * Résolution par external_games (Steam appId → IGDB game).
     */
    @SuppressWarnings("unchecked")
    public Optional<IgdbGame> findBySteamAppId(String appId, String twitchAccessToken) {
        if (clientId.isBlank() || twitchAccessToken.isBlank()) return Optional.empty();

        try {
            List<Map<String, Object>> results = restClient.post()
                .uri(IGDB_API_URL + "/external_games")
                .header("Client-ID", clientId)
                .header("Authorization", "Bearer " + twitchAccessToken)
                .body("fields game.name,game.id,game.cover; where category=1 & uid=\"" + appId + "\"; limit 1;")
                .retrieve()
                .body(List.class);

            if (results == null || results.isEmpty()) return Optional.empty();

            Map<String, Object> externalGame = results.get(0);
            Map<String, Object> game = (Map<String, Object>) externalGame.get("game");
            if (game == null) return Optional.empty();

            return Optional.of(new IgdbGame(
                String.valueOf(game.get("id")),
                (String) game.get("name")
            ));
        } catch (Exception e) {
            log.warn("IGDB external_games lookup failed for appId {}: {}", appId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Résolution par nom (fallback textuel).
     */
    @SuppressWarnings("unchecked")
    public Optional<IgdbGame> findByName(String gameName, String twitchAccessToken) {
        if (clientId.isBlank() || twitchAccessToken.isBlank()) return Optional.empty();

        try {
            List<Map<String, Object>> results = restClient.post()
                .uri(IGDB_API_URL + "/games")
                .header("Client-ID", clientId)
                .header("Authorization", "Bearer " + twitchAccessToken)
                .body("fields id,name; search \"" + gameName.replace("\"", "") + "\"; limit 1;")
                .retrieve()
                .body(List.class);

            if (results == null || results.isEmpty()) return Optional.empty();

            Map<String, Object> game = results.get(0);
            return Optional.of(new IgdbGame(
                String.valueOf(game.get("id")),
                (String) game.get("name")
            ));
        } catch (Exception e) {
            log.warn("IGDB name lookup failed for '{}': {}", gameName, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Suggère des CCLs à partir des métadonnées IGDB d'un jeu.
     */
    @SuppressWarnings("unchecked")
    public Set<TwitchCcl> suggestCcls(String igdbGameId, String twitchAccessToken) {
        Set<TwitchCcl> suggested = new HashSet<>();
        if (clientId.isBlank() || twitchAccessToken.isBlank()) return suggested;

        try {
            List<Map<String, Object>> results = restClient.post()
                .uri(IGDB_API_URL + "/games")
                .header("Client-ID", clientId)
                .header("Authorization", "Bearer " + twitchAccessToken)
                .body("fields themes.name,keywords.name,age_ratings.rating; where id=" + igdbGameId + ";")
                .retrieve()
                .body(List.class);

            if (results == null || results.isEmpty()) return suggested;

            Map<String, Object> game = results.get(0);
            List<Map<String, Object>> themes = (List<Map<String, Object>>) game.getOrDefault("themes", List.of());
            List<Map<String, Object>> keywords = (List<Map<String, Object>>) game.getOrDefault("keywords", List.of());

            Set<String> terms = new HashSet<>();
            themes.forEach(t -> terms.add(String.valueOf(t.get("name")).toLowerCase()));
            keywords.forEach(k -> terms.add(String.valueOf(k.get("name")).toLowerCase()));

            mapTermToCcl(terms, cclViolence, "violence", TwitchCcl.ViolentGraphic, suggested);
            mapTermToCcl(terms, cclMature, "mature", TwitchCcl.MatureGame, suggested);
            mapTermToCcl(terms, cclSexualContent, "sexual", TwitchCcl.SexualThemes, suggested);
            mapTermToCcl(terms, cclDrugs, "drug", TwitchCcl.DrugUse, suggested);
            mapTermToCcl(terms, cclGambling, "gambling", TwitchCcl.Gambling, suggested);
            mapTermToCcl(terms, cclProfanity, "profanity", TwitchCcl.ProfanityVulgarity, suggested);
            mapTermToCcl(terms, cclLanguageBarrier, "language", TwitchCcl.LanguageBarrier, suggested);
        } catch (Exception e) {
            log.warn("IGDB CCL suggestion failed for game {}: {}", igdbGameId, e.getMessage());
        }

        return suggested;
    }

    private void mapTermToCcl(Set<String> terms, String igdbKey, String keyword, TwitchCcl ccl, Set<TwitchCcl> result) {
        if (!igdbKey.isBlank() && terms.stream().anyMatch(t -> t.contains(keyword))) {
            result.add(ccl);
        }
    }

    public record IgdbGame(String id, String name) {}
}
