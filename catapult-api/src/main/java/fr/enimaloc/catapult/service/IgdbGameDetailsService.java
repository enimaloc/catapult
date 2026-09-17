package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.IgdbGameDetails;
import fr.enimaloc.catapult.repository.IgdbGameDetailsRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import proto.ExternalGame;
import proto.Franchise;
import proto.Game;
import proto.GameMode;
import proto.GameVideo;
import proto.Genre;
import proto.Keyword;
import proto.Platform;
import proto.PlayerPerspective;
import proto.Screenshot;
import proto.Theme;
import proto.Website;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * Service exposing enriched IGDB game details (slug, summary, release date,
 * websites, external IDs) with a stale-while-revalidate cache backed by the
 * {@code igdb_game_details} table.
 */
@Slf4j
@Service
public class IgdbGameDetailsService {

    private final IgdbGameDetailsRepository repository;
    private final IgdbClient igdbClient;
    private final IgdbService igdbService;
    private final MeterRegistry meterRegistry;
    private final Executor refreshExecutor;

    @Value("${app.igdb.details-cache-ttl-hours:168}")
    private int cacheTtlHours;

    public IgdbGameDetailsService(IgdbGameDetailsRepository repository,
                                  IgdbClient igdbClient,
                                  IgdbService igdbService,
                                  MeterRegistry meterRegistry,
                                  @Qualifier("igdbRefreshExecutor") Executor refreshExecutor) {
        this.repository = repository;
        this.igdbClient = igdbClient;
        this.igdbService = igdbService;
        this.meterRegistry = meterRegistry;
        this.refreshExecutor = refreshExecutor;
    }

    /**
     * Returns the enriched details for the given IGDB game id, hitting the cache
     * when possible. Stale entries are served immediately while a background
     * refresh is triggered.
     */
    public Optional<IgdbGameDetails> getDetails(String igdbId) {
        Optional<IgdbGameDetails> cached = repository.findById(igdbId);
        Instant threshold = Instant.now().minus(Duration.ofHours(cacheTtlHours));

        if (cached.isPresent()) {
            IgdbGameDetails entry = cached.get();
            if (entry.getFetchedAt() != null && entry.getFetchedAt().isAfter(threshold)) {
                meterRegistry.counter("catapult.igdb.details.serve", "outcome", "fresh").increment();
                return Optional.of(entry);
            }
            meterRegistry.counter("catapult.igdb.details.serve", "outcome", "stale").increment();
            refreshExecutor.execute(() -> refresh(igdbId));
            return Optional.of(entry);
        }

        meterRegistry.counter("catapult.igdb.details.serve", "outcome", "miss").increment();
        return fetchAndStore(igdbId);
    }

    private Optional<IgdbGameDetails> fetchAndStore(String igdbId) {
        String token = igdbService.getAppToken();
        if (token == null || token.isBlank()) {
            log.warn("[IGDB-details] empty app token, cannot fetch details for {}", igdbId);
            return Optional.empty();
        }
        Optional<Game> game = igdbClient.fetchGameDetails(igdbId, token);
        if (game.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(persistFromProto(igdbId, game.get()));
    }

    private void refresh(String igdbId) {
        try {
            fetchAndStore(igdbId);
        } catch (Exception e) {
            log.warn("[IGDB-details] async refresh failed for {}: {}", igdbId, e.getMessage());
        }
    }

    private IgdbGameDetails persistFromProto(String igdbId, Game game) {
        IgdbGameDetails entity = new IgdbGameDetails();
        entity.setIgdbId(igdbId);
        entity.setSlug(game.getSlug());
        entity.setSummary(game.getSummary());
        if (game.hasFirstReleaseDate() && game.getFirstReleaseDate().getSeconds() > 0) {
            entity.setFirstReleaseDate(Instant.ofEpochSecond(game.getFirstReleaseDate().getSeconds()));
        }
        entity.setWebsites(extractWebsites(game));
        // proto3 scalar doubles have no presence tracking (no hasRating()/hasAggregatedRating()) —
        // 0 is indistinguishable from "IGDB omitted the field", so treat <= 0 as unset.
        entity.setRating(game.getRating() > 0 ? game.getRating() : null);
        entity.setAggregatedRating(game.getAggregatedRating() > 0 ? game.getAggregatedRating() : null);
        entity.setPlatforms(game.getPlatformsList().stream().map(Platform::getName).toList());
        entity.setDlcNames(game.getDlcsList().stream().map(Game::getName).toList());
        entity.setDlcIds(game.getDlcsList().stream().map(g -> String.valueOf(g.getId())).toList());
        entity.setSimilarGameNames(game.getSimilarGamesList().stream().map(Game::getName).toList());
        entity.setSimilarGameIds(game.getSimilarGamesList().stream().map(g -> String.valueOf(g.getId())).toList());
        entity.setGenres(game.getGenresList().stream().map(Genre::getName).toList());
        entity.setGameModes(game.getGameModesList().stream().map(GameMode::getName).toList());
        entity.setThemes(game.getThemesList().stream().map(Theme::getName).toList());
        entity.setPlayerPerspectives(game.getPlayerPerspectivesList().stream().map(PlayerPerspective::getName).toList());
        entity.setKeywords(game.getKeywordsList().stream().map(Keyword::getName).toList());
        entity.setFranchiseNames(game.getFranchisesList().stream().map(Franchise::getName).toList());
        entity.setScreenshotUrls(game.getScreenshotsList().stream().map(Screenshot::getUrl)
                .map(IgdbGameDetailsService::normalizeUrl).toList());
        entity.setVideoIds(game.getVideosList().stream().map(GameVideo::getVideoId).toList());
        entity.setCoverUrl(game.hasCover() ? normalizeUrl(game.getCover().getUrl()) : null);
        entity.setStoryline(game.getStoryline().isBlank() ? null : game.getStoryline());
        // Same 0-vs-unset ambiguity as rating/aggregatedRating above.
        entity.setTotalRating(game.getTotalRating() > 0 ? game.getTotalRating() : null);
        entity.setTotalRatingCount(game.getTotalRatingCount() > 0 ? game.getTotalRatingCount() : null);
        entity.setFetchedAt(Instant.now());
        return repository.save(entity);
    }

    // IGDB image URLs (cover, screenshots) are protocol-relative ("//images.igdb.com/...") —
    // resolved fine in a browser <img src>, but invalid as a bare API response value.
    private static String normalizeUrl(String url) {
        return url != null && url.startsWith("//") ? "https:" + url : url;
    }

    private Map<String, String> extractWebsites(Game game) {
        Map<String, String> map = new HashMap<>();
        for (Website website : game.getWebsitesList()) {
            String category = website.getCategory().name().toLowerCase(Locale.ROOT);
            if (category.startsWith("website_")) {
                category = category.substring("website_".length());
            }
            if (website.getUrl() != null && !website.getUrl().isBlank()) {
                map.put(category, website.getUrl());
            }
        }
        for (ExternalGame external : game.getExternalGamesList()) {
            if (!external.hasExternalGameSource()) {
                continue;
            }
            String source = external.getExternalGameSource().getName();
            if (source == null || source.isBlank()) {
                continue;
            }
            String key = source.toLowerCase(Locale.ROOT);
            if (external.getUid() != null && !external.getUid().isBlank()) {
                map.put(key, external.getUid());
            }
        }
        return map;
    }
}
