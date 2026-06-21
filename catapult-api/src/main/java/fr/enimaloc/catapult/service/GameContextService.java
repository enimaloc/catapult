package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.chat.GameContext;
import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.IgdbGameDetails;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.UserSettings;
import fr.enimaloc.catapult.event.GameDetectedEvent;
import fr.enimaloc.catapult.event.NoGameDetectedEvent;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.GameBindingRepository;
import fr.enimaloc.catapult.repository.IgdbGameCclRepository;
import fr.enimaloc.catapult.repository.UserSettingsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class GameContextService {

    private final IgdbService igdbService;
    private final IgdbGameDetailsService igdbGameDetailsService;
    private final IgdbGameCclRepository igdbGameCclRepository;
    private final GameBindingRepository gameBindingRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final TwLabelService twLabelService;

    public GameContextService(IgdbService igdbService,
                              IgdbGameDetailsService igdbGameDetailsService,
                              IgdbGameCclRepository igdbGameCclRepository,
                              GameBindingRepository gameBindingRepository,
                              UserSettingsRepository userSettingsRepository,
                              TwLabelService twLabelService) {
        this.igdbService = igdbService;
        this.igdbGameDetailsService = igdbGameDetailsService;
        this.igdbGameCclRepository = igdbGameCclRepository;
        this.gameBindingRepository = gameBindingRepository;
        this.userSettingsRepository = userSettingsRepository;
        this.twLabelService = twLabelService;
    }

    private final Map<UUID, GameContext> contexts = new ConcurrentHashMap<>();

    public Optional<GameContext> get(UserAccount user) {
        return Optional.ofNullable(contexts.get(user.getId()));
    }

    @EventListener
    public void onGameDetected(GameDetectedEvent event) {
        DetectedGame detected = event.getDetectedGame();
        UserAccount user = event.getUser();
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

        GameContext ctx = new GameContext(
            detected, igdbId, detected.getSourceName(),
            summary, releaseDate, stores, activeStoreUrl, slug,
            activeTws, twLabels, ageRating
        );
        contexts.put(event.getUser().getId(), ctx);
    }

    @EventListener
    public void onNoGameDetected(NoGameDetectedEvent event) {
        contexts.remove(event.getUser().getId());
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
