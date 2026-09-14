package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.IgdbGameDetails;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.GameBindingRepository;
import fr.enimaloc.catapult.service.GameStateService;
import fr.enimaloc.catapult.service.IgdbGameDetailsService;
import fr.enimaloc.catapult.service.IgdbService;
import fr.enimaloc.catapult.service.SteamStoreService;
import fr.enimaloc.catapult.service.TwLabelService;
import fr.enimaloc.catapult.service.WidgetTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// Authenticated by the same per-user widget token as the twitchat widget (see
// WidgetTokenService) — meant for public overlay/OBS consumption, not the
// JWT-authenticated dashboard API. Deliberately not nested under /api/widget:
// this normalizes game info, it isn't itself a widget.
@RestController
@RequestMapping("/api/game")
@RequiredArgsConstructor
public class ApiGameInfoController {

    private final WidgetTokenService widgetTokenService;
    private final GameStateService gameStateService;
    private final GameBindingRepository gameBindingRepository;
    private final IgdbService igdbService;
    private final IgdbGameDetailsService igdbGameDetailsService;
    private final SteamStoreService steamStoreService;
    private final TwLabelService twLabelService;
    private final MessageSource messageSource;

    // Bumped whenever the response schema changes in a way old callers might not expect
    // (e.g. "tws" switching from raw ids to localized labels). Callers pin an older value
    // via "v" to keep receiving that schema; omitting it always tracks the latest one.
    private static final int LATEST_VERSION = 1;

    public record GameInfoResponse(String name, String igdbUrl, GameBinding.SourceType sourceType,
                                    String storeName, String storeUrl, String description,
                                    String twitchGameId, String twitchGameName,
                                    Set<String> tws, String twsJoined,
                                    Set<String> ccls, String cclsJoined,
                                    List<String> platforms, String platformsJoined,
                                    Double rating, Double aggregatedRating, Instant releaseDate,
                                    int version) {}

    // "lang" is a query param rather than relying on Accept-Language: this route is meant to be
    // pasted as a plain URL into an OBS browser source / overlay, which sends no such header.
    //
    // Open to any origin: the widget token in the URL is the only access control (same trust
    // model as pasting the URL into OBS/Twitchat/any other overlay tool), and the response carries
    // no cookies/credentials, so there's nothing origin-restriction would protect here. Twitchat's
    // "HTTP call" trigger action in particular fetches this in the background from its own page
    // context (confirmed via HAR: Origin: https://twitchat.fr) — same quirk as
    // WidgetTwitchatController.actionPage, generalized to any caller instead of one hardcoded origin.
    @CrossOrigin(origins = "*")
    @GetMapping("/{uuid}")
    public ResponseEntity<GameInfoResponse> gameInfo(@PathVariable UUID uuid,
                                                      @RequestParam(name = "lang", required = false) String lang,
                                                      @RequestParam(name = "v", required = false) Integer v) {
        Optional<UserAccount> user = widgetTokenService.resolve(uuid);
        if (user.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Optional<DetectedGame> detected = gameStateService.getLastKnownGame(user.get());
        if (detected.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(buildResponse(user.get(), detected.get(), parseLocale(lang), resolveVersion(v)));
    }

    private static Locale parseLocale(String lang) {
        return lang == null || lang.isBlank() ? Locale.ENGLISH : Locale.forLanguageTag(lang);
    }

    private static int resolveVersion(Integer v) {
        return v == null ? LATEST_VERSION : v;
    }

    private GameInfoResponse buildResponse(UserAccount user, DetectedGame detected, Locale locale, int version) {
        GameBinding binding = gameBindingRepository
                .findByUserAndSourceIdAndSourceType(user, detected.getSourceId(), detected.getSourceType())
                .orElse(null);

        Optional<IgdbGameDetails> details = resolveIgdbId(detected).flatMap(igdbGameDetailsService::getDetails);
        Set<String> tws = localizeTws(binding == null ? Set.of() : binding.getTws(), locale);
        Set<String> ccls = binding == null ? Set.of() : binding.getCcls();
        List<String> platforms = details.map(IgdbGameDetails::getPlatforms).orElse(List.of());

        return new GameInfoResponse(
                detected.getSourceName(),
                details.map(IgdbGameDetails::getSlug)
                        .map(slug -> "https://www.igdb.com/games/" + slug)
                        .orElse(null),
                detected.getSourceType(),
                storeName(detected.getSourceType(), locale),
                storeUrl(detected, details.orElse(null)),
                description(detected, locale, details).orElse(null),
                binding == null ? null : binding.getTwitchGameId(),
                binding == null ? null : binding.getTwitchGameName(),
                tws,
                joinSorted(tws),
                ccls,
                joinSorted(ccls),
                platforms,
                String.join(", ", platforms),
                details.map(IgdbGameDetails::getRating).orElse(null),
                details.map(IgdbGameDetails::getAggregatedRating).orElse(null),
                details.map(IgdbGameDetails::getFirstReleaseDate).orElse(null),
                version);
    }

    private Set<String> localizeTws(Set<String> tws, Locale locale) {
        return tws.stream().map(id -> twLabelService.resolve(id, locale)).collect(Collectors.toSet());
    }

    // The store's own description takes priority (locale-matched to the "lang" param), since it's
    // written for that specific release/platform; IGDB's summary is a generic fallback for stores
    // with no description API (or Steam with none in the requested language) so the field is
    // rarely empty. Only Steam has a description API integrated today.
    private Optional<String> description(DetectedGame detected, Locale locale, Optional<IgdbGameDetails> details) {
        Optional<String> storeDescription = detected.getSourceType() == GameBinding.SourceType.STEAM
                && detected.getSourceId() != null
                ? steamStoreService.fetchDescription(detected.getSourceId(), locale)
                : Optional.empty();
        return storeDescription.or(() -> details.map(IgdbGameDetails::getSummary));
    }

    // tws/ccls are unordered Sets, so sort for a deterministic display string;
    // platforms is already an ordered List from IGDB and is joined as-is.
    private static String joinSorted(Set<String> values) {
        return values.stream().sorted().collect(Collectors.joining(", "));
    }

    private Optional<String> resolveIgdbId(DetectedGame detected) {
        return (detected.getSourceId() != null
                ? igdbService.findByExternalAppId(detected.getSourceType(), detected.getSourceId())
                        .or(() -> igdbService.findByName(detected.getSourceName()))
                : igdbService.findByName(detected.getSourceName()))
                .map(IgdbService.IgdbGame::id);
    }

    private String storeName(GameBinding.SourceType sourceType, Locale locale) {
        return messageSource.getMessage("game_info.store." + sourceType.name(), null, locale);
    }

    // IGDB's own "steam" website category is unreliable (see extractWebsites in
    // IgdbGameDetailsService — external_games can overwrite it with a bare app id
    // instead of a URL), so Steam gets a store.steampowered.com URL built directly
    // from the detected app id rather than trusting the cached website map.
    private static String storeUrl(DetectedGame detected, IgdbGameDetails details) {
        if (detected.getSourceType() == GameBinding.SourceType.STEAM && detected.getSourceId() != null) {
            return "https://store.steampowered.com/app/" + detected.getSourceId();
        }
        return details == null ? null : details.getWebsites().get(storeWebsiteKey(detected.getSourceType()));
    }

    private static String storeWebsiteKey(GameBinding.SourceType sourceType) {
        return switch (sourceType) {
            case STEAM -> "steam";
            case BATTLENET -> "battlenet";
            default -> "";
        };
    }
}
