package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import fr.enimaloc.catapult.service.xbox.XboxUserTokenService;
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
 * Detects the current game through the Xbox Live presence API, with the user's
 * XSTS token (no service account: each user queries their own presence
 * with their own linked OAuth2 token).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("xbox.enabled")
public class XboxGameGetter implements GameGetter {

    private static final String PRESENCE_URL = "https://userpresence.xboxlive.com/users/xuid({xuid})";
    /** Titlehub (not officially documented): the only source of the Store Product ID IGDB expects, since the presence's titleId is not that identifier. */
    private static final String TITLE_DETAIL_URL = "https://titlehub.xboxlive.com/users/xuid({xuid})/titles/titleid({titleId})/decoration/detail";
    /** Title standing for the Xbox dashboard itself, never a game in progress. */
    private static final String DASHBOARD_TITLE_NAME = "Home";
    /** Xbox Live titleId of Minecraft (legacy Win32 build, with no Store Product ID); detected separately by {@link MinecraftPresenceGetter} when active. */
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
                return Optional.empty();
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

    /** Resolves a titleId's Store Product ID (the format IGDB expects) through Titlehub; falls back to the titleId if the resolution fails. */
    @SuppressWarnings("unchecked")
    private Optional<String> resolveProductId(XboxUserTokenService.XstsSession session, String titleId) {
        return apiObservations.observe("xbox", "get_title_detail", () -> {
            try {
                Map<String, Object> response = restClient.get()
                    .uri(TITLE_DETAIL_URL, session.xuid(), titleId)
                    .header("Authorization", "XBL3.0 x=" + session.userHash() + ";" + session.token())
                    .header("x-xbl-contract-version", "2")
                    .header("Accept-Language", "en-US") // Required by Titlehub (400 without a valid locale); determines no displayed content.
                    .retrieve()
                    .body(Map.class);
                return extractProductId(response);
            } catch (Exception e) {
                log.warn("Failed to fetch Xbox title detail for titleId {}: {}", titleId, e.getMessage());
                return Optional.empty();
            }
        });
    }

    @SuppressWarnings("unchecked")
    private Optional<String> extractProductId(Map<String, Object> response) {
        if (response == null) return Optional.empty();
        List<Map<String, Object>> titles = (List<Map<String, Object>>) response.get("titles");
        if (titles == null || titles.isEmpty()) return Optional.empty();
        Map<String, Object> detail = (Map<String, Object>) titles.getFirst().get("detail");
        if (detail == null) return Optional.empty();
        List<Map<String, Object>> availabilities = (List<Map<String, Object>>) detail.get("availabilities");
        if (availabilities == null || availabilities.isEmpty()) return Optional.empty();
        Object availabilityId = availabilities.getFirst().get("AvailabilityId");
        return availabilityId == null ? Optional.empty() : Optional.of(String.valueOf(availabilityId));
    }

    private record TitlePresence(String titleId, String titleName) {}
}
