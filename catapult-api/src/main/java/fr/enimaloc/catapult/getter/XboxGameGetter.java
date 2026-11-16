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

import java.util.ArrayList;
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
    /**
     * Titles standing for the Xbox app/shell itself (not a game in progress): "Home" on console,
     * "Online" for the Xbox app on PC/mobile/companion. Their titleId varies by platform (unlike
     * {@link #titleIdBlacklist}, which only covers the console dashboard), so they are also filtered by name.
     */
    private static final Set<String> DASHBOARD_TITLE_NAMES = Set.of("Home", "Online");
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

        return tokenService.getToken(user).flatMap(session -> {
            for (TitlePresence title : fetchActiveTitles(user, session)) {
                TitleDetail detail = resolveTitleDetail(session, title.titleId());
                if (detail.isApp()) {
                    log.debug("Skipping Xbox title {} ({}): Titlehub lists it as {}, not a game",
                        title.titleId(), title.titleName(), detail.type());
                    continue;
                }
                String productId = detail.productId().orElse(title.titleId());
                return Optional.of(new DetectedGame(productId, GameBinding.SourceType.XBOX, title.titleName()));
            }
            return Optional.empty();
        });
    }

    @SuppressWarnings("unchecked")
    private List<TitlePresence> fetchActiveTitles(UserAccount user, XboxUserTokenService.XstsSession session) {
        return apiObservations.observe("xbox", "get_presence", () -> {
            try {
                Map<String, Object> response = restClient.get()
                    .uri(PRESENCE_URL + "?level=all", session.xuid())
                    .header("Authorization", "XBL3.0 x=" + session.userHash() + ";" + session.token())
                    .header("x-xbl-contract-version", "3")
                    .retrieve()
                    .body(Map.class);
                return extractActiveTitles(response);
            } catch (Exception e) {
                log.warn("Failed to fetch Xbox presence for user {}: {}", user.getId(), e.getMessage());
                return List.of();
            }
        });
    }

    @SuppressWarnings("unchecked")
    private List<TitlePresence> extractActiveTitles(Map<String, Object> response) {
        List<TitlePresence> active = new ArrayList<>();
        if (response == null) return active;
        List<Map<String, Object>> devices = (List<Map<String, Object>>) response.get("devices");
        if (devices == null) return active;

        for (Map<String, Object> device : devices) {
            List<Map<String, Object>> titles = (List<Map<String, Object>>) device.get("titles");
            if (titles == null) continue;
            for (Map<String, Object> title : titles) {
                String titleName = (String) title.get("name");
                String state = (String) title.get("state");
                if (titleName == null || DASHBOARD_TITLE_NAMES.contains(titleName)) continue;
                if (!"Active".equals(state)) continue;
                String titleId = String.valueOf(title.get("id"));
                if (titleIdBlacklist.contains(titleId)) continue;
                active.add(new TitlePresence(titleId, titleName));
            }
        }
        return active;
    }

    /**
     * Fetches a title's Titlehub detail in one call: its type ("Game", "App"...) and its Store Product ID
     * (the format IGDB expects). Falls back to an empty detail if the call fails.
     */
    @SuppressWarnings("unchecked")
    private TitleDetail resolveTitleDetail(XboxUserTokenService.XstsSession session, String titleId) {
        return apiObservations.observe("xbox", "get_title_detail", () -> {
            try {
                Map<String, Object> response = restClient.get()
                    .uri(TITLE_DETAIL_URL, session.xuid(), titleId)
                    .header("Authorization", "XBL3.0 x=" + session.userHash() + ";" + session.token())
                    .header("x-xbl-contract-version", "2")
                    .header("Accept-Language", "en-US") // Required by Titlehub (400 without a valid locale); determines no displayed content.
                    .retrieve()
                    .body(Map.class);
                return extractTitleDetail(response);
            } catch (Exception e) {
                log.warn("Failed to fetch Xbox title detail for titleId {}: {}", titleId, e.getMessage());
                return TitleDetail.EMPTY;
            }
        });
    }

    @SuppressWarnings("unchecked")
    private TitleDetail extractTitleDetail(Map<String, Object> response) {
        if (response == null) return TitleDetail.EMPTY;
        List<Map<String, Object>> titles = (List<Map<String, Object>>) response.get("titles");
        if (titles == null || titles.isEmpty()) return TitleDetail.EMPTY;
        Map<String, Object> first = titles.getFirst();
        Object type = first.get("type");
        String typeName = type == null ? null : String.valueOf(type);
        Optional<String> productId = Optional.empty();
        Map<String, Object> detail = (Map<String, Object>) first.get("detail");
        if (detail != null) {
            List<Map<String, Object>> availabilities = (List<Map<String, Object>>) detail.get("availabilities");
            if (availabilities != null && !availabilities.isEmpty()) {
                Object availabilityId = availabilities.getFirst().get("AvailabilityId");
                if (availabilityId != null) productId = Optional.of(String.valueOf(availabilityId));
            }
        }
        return new TitleDetail(typeName, productId);
    }

    private record TitlePresence(String titleId, String titleName) {}

    /** Titlehub's view of a title; {@code type} is null when Titlehub did not answer or gave none. */
    private record TitleDetail(String type, Optional<String> productId) {
        static final TitleDetail EMPTY = new TitleDetail(null, Optional.empty());

        /** A non-blank type other than "Game" means a non-game app (Microsoft Store, Twitch, Netflix...). */
        boolean isApp() {
            return type != null && !type.isBlank() && !"Game".equalsIgnoreCase(type.trim());
        }
    }
}
