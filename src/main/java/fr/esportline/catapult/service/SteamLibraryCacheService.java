package fr.esportline.catapult.service;

import fr.esportline.catapult.domain.UserAccount;
import fr.esportline.catapult.event.SteamLinkedEvent;
import fr.esportline.catapult.repository.UserAccountRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@DependsOn("flyway")
@RequiredArgsConstructor
public class SteamLibraryCacheService {

    private static final String OWNED_GAMES_URL =
        "https://api.steampowered.com/IPlayerService/GetOwnedGames/v0001/";

    @Value("${steam.api-key:}")
    private String steamApiKey;

    private final RestClient restClient;
    private final IgdbService igdbService;
    private final UserAccountRepository userAccountRepository;

    @Async
    @PostConstruct
    public void preloadAllUserLibraries() {
        List<UserAccount> users = userAccountRepository.findBySteamIdNotNull();
        if (users.isEmpty()) {
            log.info("No users with Steam linked — skipping library preload");
        } else {
            log.info("Pre-caching Steam libraries for {} user(s) at startup", users.size());
            for (UserAccount user : users) {
                cacheLibrary(user);
            }
        }
        igdbService.prewarmCclCache();
    }

    @Async
    @EventListener
    public void onSteamLinked(SteamLinkedEvent event) {
        cacheLibrary(event.getUser());
        igdbService.prewarmCclCache();
    }

    private void cacheLibrary(UserAccount user) {
        if (steamApiKey.isBlank() || user.getSteamId() == null) return;

        log.info("Pre-caching Steam library for user {} (steamId={})", user.getId(), user.getSteamId());
        List<Map<String, Object>> games = fetchOwnedGames(user.getSteamId());
        if (games.isEmpty()) {
            log.warn("No owned games returned for steamId={} (private profile?)", user.getSteamId());
            return;
        }

        List<String> appIds = games.stream()
            .map(g -> g.get("appid"))
            .filter(id -> id != null)
            .map(String::valueOf)
            .collect(Collectors.toList());

        igdbService.prewarmSteamAppIds(appIds);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchOwnedGames(String steamId) {
        try {
            String url = UriComponentsBuilder.fromUriString(OWNED_GAMES_URL)
                .queryParam("key", steamApiKey)
                .queryParam("steamid", steamId)
                .queryParam("include_appinfo", 1)
                .toUriString();

            Map<String, Object> response = restClient.get()
                .uri(url)
                .retrieve()
                .body(Map.class);

            if (response == null) return List.of();
            Map<String, Object> body = (Map<String, Object>) response.get("response");
            if (body == null) return List.of();

            List<Map<String, Object>> games = (List<Map<String, Object>>) body.get("games");
            return games != null ? games : List.of();
        } catch (Exception e) {
            log.warn("Failed to fetch owned Steam games for steamId={}: {}", steamId, e.getMessage());
            return List.of();
        }
    }
}
