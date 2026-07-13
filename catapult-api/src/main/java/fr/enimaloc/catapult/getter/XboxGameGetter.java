package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.service.XboxUserTokenService;
import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Détecte le jeu en cours via l'API de présence Xbox Live, avec le token
 * XSTS de l'utilisateur (pas de compte de service : chaque utilisateur
 * interroge sa propre présence avec son propre token OAuth2 lié).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("xbox.enabled")
public class XboxGameGetter implements GameGetter {

    private static final String PRESENCE_URL = "https://userpresence.xboxlive.com/users/xuid({xuid})";
    /** Titre représentant le tableau de bord Xbox lui-même, jamais une partie en cours. */
    private static final String DASHBOARD_TITLE_NAME = "Home";

    private final RestClient restClient;
    private final OAuthTokenRepository oAuthTokenRepository;
    private final XboxUserTokenService tokenService;
    private final ExternalApiObservations apiObservations;

    @Value("${xbox.title-id-blacklist:1626579248}")
    private String titleIdBlacklistRaw;

    private Set<String> titleIdBlacklist;

    @PostConstruct
    void init() {
        titleIdBlacklist = Arrays.stream(titleIdBlacklistRaw.split(","))
            .map(String::trim)
            .filter(id -> !id.isEmpty())
            .collect(Collectors.toSet());
    }

    @Override
    public String name() {
        return "Xbox";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<DetectedGame> getCurrentGame(UserAccount user) {
        Optional<OAuthToken> linked = oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.XBOX);
        if (linked.isEmpty()) {
            return Optional.empty();
        }

        return tokenService.getToken(user).flatMap(session ->
            apiObservations.observe("xbox", "get_presence", () -> {
                try {
                    Map<String, Object> response = restClient.get()
                        .uri(PRESENCE_URL + "?level=all", session.xuid())
                        .header("Authorization", "XBL3.0 x=" + session.userHash() + ";" + session.token())
                        .header("x-xbl-contract-version", "3")
                        .retrieve()
                        .body(Map.class);
                    return extractCurrentTitle(response);
                } catch (Exception e) {
                    log.warn("Failed to fetch Xbox presence for user {}: {}", user.getId(), e.getMessage());
                    return Optional.<DetectedGame>empty();
                }
            })
        );
    }

    @SuppressWarnings("unchecked")
    private Optional<DetectedGame> extractCurrentTitle(Map<String, Object> response) {
        if (response == null) return Optional.empty();
        List<Map<String, Object>> devices = (List<Map<String, Object>>) response.get("devices");
        if (devices == null) return Optional.empty();

        for (Map<String, Object> device : devices) {
            List<Map<String, Object>> titles = (List<Map<String, Object>>) device.get("titles");
            if (titles == null) continue;
            for (Map<String, Object> title : titles) {
                String titleName = (String) title.get("name");
                String state = (String) title.get("state");
                if (titleName == null || DASHBOARD_TITLE_NAME.equals(titleName)) continue;
                if (!"Active".equals(state)) continue;
                String titleId = String.valueOf(title.get("id"));
                if (titleIdBlacklist.contains(titleId)) continue;
                return Optional.of(new DetectedGame(titleId, GameBinding.SourceType.XBOX, titleName));
            }
        }
        return Optional.empty();
    }
}
