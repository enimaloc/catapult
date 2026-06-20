package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.chat.GameContext;
import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.IgdbGameDetails;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.event.GameDetectedEvent;
import fr.enimaloc.catapult.event.NoGameDetectedEvent;
import fr.enimaloc.catapult.getter.DetectedGame;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class GameContextService {

    private final IgdbService igdbService;
    private final IgdbGameDetailsService igdbGameDetailsService;

    private final Map<UUID, GameContext> contexts = new ConcurrentHashMap<>();

    public Optional<GameContext> get(UserAccount user) {
        return Optional.ofNullable(contexts.get(user.getId()));
    }

    @EventListener
    public void onGameDetected(GameDetectedEvent event) {
        DetectedGame detected = event.getDetectedGame();
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

        GameContext ctx = new GameContext(
            detected, igdbId, detected.getSourceName(),
            summary, releaseDate, stores, activeStoreUrl, slug
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
}
