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
import java.util.HashSet;
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
    /** Titlehub (non documentée officiellement) : seule source du Product ID Store attendu par IGDB, le titleId de la présence n'étant pas cet identifiant. */
    private static final String TITLE_DETAIL_URL = "https://titlehub.xboxlive.com/users/xuid({xuid})/titles/titleid({titleId})/decoration/detail";
    /** Titre représentant le tableau de bord Xbox lui-même, jamais une partie en cours. */
    private static final String DASHBOARD_TITLE_NAME = "Home";
    /** titleId Xbox Live de Minecraft (build Win32 legacy, sans Product ID Store) ; détecté séparément par {@link MinecraftPresenceGetter} quand actif. */
    private static final String MINECRAFT_XBOX_TITLE_ID = "1791712750";

    private final RestClient restClient;
    private final OAuthTokenRepository oAuthTokenRepository;
    private final XboxUserTokenService tokenService;
    private final ExternalApiObservations apiObservations;
    private final Optional<MinecraftPresenceGetter> minecraftPresenceGetter;

    @Value("${xbox.title-id-blacklist:1626579248}")
    private String titleIdBlacklistRaw;

    private Set<String> titleIdBlacklist;

    @PostConstruct
    void init() {
        titleIdBlacklist = Arrays.stream(titleIdBlacklistRaw.split(","))
            .map(String::trim)
            .filter(id -> !id.isEmpty())
            .collect(Collectors.toCollection(HashSet::new));
        if (minecraftPresenceGetter.isPresent()) {
            titleIdBlacklist.add(MINECRAFT_XBOX_TITLE_ID);
        }
    }

    @Override
    public String name() {
        return "Xbox";
    }

    @Override
    public Optional<DetectedGame> getCurrentGame(UserAccount user) {
        Optional<OAuthToken> linked = oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.XBOX);
        if (linked.isEmpty()) {
            return Optional.empty();
        }

        return tokenService.getToken(user).flatMap(session -> fetchCurrentTitle(user, session)
            .map(title -> {
                String productId = resolveProductId(session, title.titleId()).orElse(title.titleId());
                return new DetectedGame(productId, GameBinding.SourceType.XBOX, title.titleName());
            })
        );
    }

    @SuppressWarnings("unchecked")
    private Optional<TitlePresence> fetchCurrentTitle(UserAccount user, XboxUserTokenService.XstsSession session) {
        return apiObservations.observe("xbox", "get_presence", () -> {
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
                return Optional.<TitlePresence>empty();
            }
        });
    }

    @SuppressWarnings("unchecked")
    private Optional<TitlePresence> extractCurrentTitle(Map<String, Object> response) {
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
                return Optional.of(new TitlePresence(titleId, titleName));
            }
        }
        return Optional.empty();
    }

    /** Résout le Product ID Store (format attendu par IGDB) d'un titleId via Titlehub ; retombe sur le titleId si la résolution échoue. */
    @SuppressWarnings("unchecked")
    private Optional<String> resolveProductId(XboxUserTokenService.XstsSession session, String titleId) {
        return apiObservations.observe("xbox", "get_title_detail", () -> {
            try {
                Map<String, Object> response = restClient.get()
                    .uri(TITLE_DETAIL_URL, session.xuid(), titleId)
                    .header("Authorization", "XBL3.0 x=" + session.userHash() + ";" + session.token())
                    .header("x-xbl-contract-version", "2")
                    .header("Accept-Language", "en-US") // Requis par Titlehub (400 sans locale valide) ; ne conditionne aucun contenu affiché.
                    .retrieve()
                    .body(Map.class);
                return extractProductId(response);
            } catch (Exception e) {
                log.warn("Failed to fetch Xbox title detail for titleId {}: {}", titleId, e.getMessage());
                return Optional.<String>empty();
            }
        });
    }

    @SuppressWarnings("unchecked")
    private Optional<String> extractProductId(Map<String, Object> response) {
        if (response == null) return Optional.empty();
        List<Map<String, Object>> titles = (List<Map<String, Object>>) response.get("titles");
        if (titles == null || titles.isEmpty()) return Optional.empty();
        Map<String, Object> detail = (Map<String, Object>) titles.get(0).get("detail");
        if (detail == null) return Optional.empty();
        List<Map<String, Object>> availabilities = (List<Map<String, Object>>) detail.get("availabilities");
        if (availabilities == null || availabilities.isEmpty()) return Optional.empty();
        Object availabilityId = availabilities.get(0).get("AvailabilityId");
        return availabilityId == null ? Optional.empty() : Optional.of(String.valueOf(availabilityId));
    }

    private record TitlePresence(String titleId, String titleName) {}
}
