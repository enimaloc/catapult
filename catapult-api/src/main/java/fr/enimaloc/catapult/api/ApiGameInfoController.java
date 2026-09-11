package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.IgdbGameDetails;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.GameBindingRepository;
import fr.enimaloc.catapult.service.GameStateService;
import fr.enimaloc.catapult.service.IgdbGameDetailsService;
import fr.enimaloc.catapult.service.IgdbService;
import fr.enimaloc.catapult.service.WidgetTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.http.ResponseEntity;
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
    private final MessageSource messageSource;

    public record GameInfoResponse(String name, String igdbUrl, GameBinding.SourceType sourceType,
                                    String storeName, String storeUrl, String description,
                                    String twitchGameId, String twitchGameName,
                                    Set<String> tws, String twsJoined,
                                    Set<String> ccls, String cclsJoined,
                                    List<String> platforms, String platformsJoined,
                                    Double rating, Double aggregatedRating, Instant releaseDate) {}

    // "lang" is a query param rather than relying on Accept-Language: this route is meant to be
    // pasted as a plain URL into an OBS browser source / overlay, which sends no such header.
    @GetMapping("/{uuid}")
    public ResponseEntity<GameInfoResponse> gameInfo(@PathVariable UUID uuid,
                                                      @RequestParam(name = "lang", required = false) String lang) {
        Optional<UserAccount> user = widgetTokenService.resolve(uuid);
        if (user.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Optional<DetectedGame> detected = gameStateService.getLastKnownGame(user.get());
        if (detected.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(buildResponse(user.get(), detected.get(), parseLocale(lang)));
    }

    private static Locale parseLocale(String lang) {
        return lang == null || lang.isBlank() ? Locale.ENGLISH : Locale.forLanguageTag(lang);
    }

    private GameInfoResponse buildResponse(UserAccount user, DetectedGame detected, Locale locale) {
        GameBinding binding = gameBindingRepository
                .findByUserAndSourceIdAndSourceType(user, detected.getSourceId(), detected.getSourceType())
                .orElse(null);

        Optional<IgdbGameDetails> details = resolveIgdbId(detected).flatMap(igdbGameDetailsService::getDetails);
        Set<String> tws = binding == null ? Set.of() : binding.getTws();
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
                details.map(IgdbGameDetails::getSummary).orElse(null),
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
                details.map(IgdbGameDetails::getFirstReleaseDate).orElse(null));
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
