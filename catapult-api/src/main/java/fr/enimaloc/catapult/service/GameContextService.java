package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.chat.GameContext;
import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.IgdbGameDetails;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.UserSettings;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.GameBindingRepository;
import fr.enimaloc.catapult.repository.IgdbGameCclRepository;
import fr.enimaloc.catapult.repository.UserSettingsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class GameContextService {

    private final GameStateService gameStateService;
    private final IgdbService igdbService;
    private final IgdbGameDetailsService igdbGameDetailsService;
    private final IgdbGameCclRepository igdbGameCclRepository;
    private final GameBindingRepository gameBindingRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final TwLabelService twLabelService;

    public GameContextService(GameStateService gameStateService,
                              IgdbService igdbService,
                              IgdbGameDetailsService igdbGameDetailsService,
                              IgdbGameCclRepository igdbGameCclRepository,
                              GameBindingRepository gameBindingRepository,
                              UserSettingsRepository userSettingsRepository,
                              TwLabelService twLabelService) {
        this.gameStateService = gameStateService;
        this.igdbService = igdbService;
        this.igdbGameDetailsService = igdbGameDetailsService;
        this.igdbGameCclRepository = igdbGameCclRepository;
        this.gameBindingRepository = gameBindingRepository;
        this.userSettingsRepository = userSettingsRepository;
        this.twLabelService = twLabelService;
    }

    private record CachedContext(DetectedGame detected, GameContext context) {}

    private final Map<UUID, CachedContext> cache = new ConcurrentHashMap<>();

    public Optional<GameContext> get(UserAccount user) {
        Optional<DetectedGame> detectedOpt = gameStateService.getLastKnownGame(user);
        if (detectedOpt.isEmpty()) {
            log.debug("[GameContext] no last-known game for user {} (twitchId={}) — placeholders will be empty",
                    user.getId(), user.getTwitchId());
            cache.remove(user.getId());
            return Optional.empty();
        }
        DetectedGame detected = detectedOpt.get();
        CachedContext existing = cache.get(user.getId());
        if (existing != null && isFresh(existing, detected)) {
            return Optional.of(existing.context());
        }
        GameContext fresh;
        try {
            fresh = buildContext(user, detected);
        } catch (Exception e) {
            log.warn("Failed to build GameContext for user {} (game {}): {}",
                user.getId(), detected.getSourceName(), e.getMessage());
            return Optional.of(minimalContext(detected));
        }
        cache.put(user.getId(), new CachedContext(detected, fresh));
        return Optional.of(fresh);
    }

    private boolean isFresh(CachedContext cached, DetectedGame current) {
        DetectedGame prev = cached.detected();
        return Objects.equals(prev.getSourceId(), current.getSourceId())
            && prev.getSourceType() == current.getSourceType();
    }

    private GameContext buildContext(UserAccount user, DetectedGame detected) {
        String igdbId = resolveIgdbId(detected).orElse(null);
        IgdbGameDetails details = (igdbId != null)
            ? igdbGameDetailsService.getDetails(igdbId).orElse(null)
            : null;

        String summary = details != null ? details.getSummary() : null;
        LocalDate releaseDate = (details != null && details.getFirstReleaseDate() != null)
            ? details.getFirstReleaseDate().atZone(ZoneOffset.UTC).toLocalDate()
            : null;
        Map<String, String> stores = details != null && details.getWebsites() != null
            ? details.getWebsites()
            : Map.of();
        String activeStoreUrl = stores.get(GameContext.storeKey(detected.getSourceType()));
        String slug = details != null ? details.getSlug() : null;
        Double rating = details != null ? details.getRating() : null;
        Double criticRating = details != null ? details.getAggregatedRating() : null;
        List<String> platforms = details != null && details.getPlatforms() != null
            ? details.getPlatforms() : List.of();
        List<String> dlcNames = details != null && details.getDlcNames() != null
            ? details.getDlcNames() : List.of();
        List<String> similarGameNames = details != null && details.getSimilarGameNames() != null
            ? details.getSimilarGameNames() : List.of();

        String ageRating = igdbId == null ? null
            : igdbGameCclRepository.findById(igdbId)
                .map(c -> c.getAgeRatings())
                .filter(s -> s != null && !s.isBlank())
                .orElse(null);

        GameBinding binding = (gameBindingRepository != null)
            ? gameBindingRepository.findByUserAndSourceIdAndSourceType(
                user, detected.getSourceId(), detected.getSourceType()).orElse(null)
            : null;
        UserSettings userSettings = (userSettingsRepository != null && user.getId() != null)
            ? userSettingsRepository.findById(user.getId()).orElse(null)
            : null;

        Set<String> activeTws = Collections.emptySet();
        Map<String, String> twLabels = Collections.emptyMap();
        if (binding != null && binding.isTwEnabled()
                && userSettings != null && userSettings.isTwFeatureEnabled()
                && twLabelService != null) {
            Set<String> raw = new HashSet<>(binding.getTws() == null ? Set.of() : binding.getTws());
            if (userSettings.getBlockedTws() != null) {
                raw.removeAll(userSettings.getBlockedTws());
            }
            activeTws = raw;
            Locale locale = resolveLocale(user);
            Map<String, String> labels = new HashMap<>();
            for (String twId : raw) {
                labels.put(twId, twLabelService.resolve(twId, locale));
            }
            twLabels = labels;
        }

        return new GameContext(
            detected, igdbId, detected.getSourceName(),
            summary, releaseDate, stores, activeStoreUrl, slug,
            activeTws, twLabels, ageRating,
            rating, criticRating, platforms, dlcNames, similarGameNames
        );
    }

    private GameContext minimalContext(DetectedGame detected) {
        return new GameContext(
            detected, null, detected.getSourceName(),
            null, null, Map.of(), null, null,
            Collections.emptySet(), Collections.emptyMap(), null,
            null, null, List.of(), List.of(), List.of()
        );
    }

    private Optional<String> resolveIgdbId(DetectedGame detected) {
        if (detected.getSourceType() == GameBinding.SourceType.STEAM) {
            return igdbService.findBySteamAppId(detected.getSourceId()).map(IgdbService.IgdbGame::id);
        }
        return igdbService.findByName(detected.getSourceName()).map(IgdbService.IgdbGame::id);
    }

    private Locale resolveLocale(UserAccount user) {
        // No per-user locale exposed yet; default to FRANCE consistent with DynamicCommandResolver.
        return Locale.FRANCE;
    }
}
