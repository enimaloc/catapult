package fr.enimaloc.catapult.service.enrichment.twitch;

import fr.enimaloc.catapult.domain.twitch.TwitchCategoryCache;
import fr.enimaloc.catapult.repository.twitch.TwitchCategoryCacheRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The Twitch activity of a game's category. The category (local table first, then Helix), the lives and the clips
 * are cached separately; the {@link TwitchActivity} is assembled on each call. A missing category is the only
 * absence: a category without a live is an activity with zero lives. Without Twitch configured nothing is read
 * or cached.
 */
@Service
public class TwitchActivityService {

    private static final Duration CLIPS_WINDOW = Duration.ofDays(7);

    private final EnrichmentCache cache;
    private final TwitchActivityClient client;
    private final TwitchCategoryCacheRepository categories;
    private final Clock clock;

    @Autowired
    public TwitchActivityService(EnrichmentCache cache, TwitchActivityClient client,
                                 TwitchCategoryCacheRepository categories) {
        this(cache, client, categories, Clock.systemUTC());
    }

    TwitchActivityService(EnrichmentCache cache, TwitchActivityClient client,
                          TwitchCategoryCacheRepository categories, Clock clock) {
        this.cache      = cache;
        this.client     = client;
        this.categories = categories;
        this.clock      = clock;
    }

    /** The activity of the category matching an IGDB game id; empty when Twitch has none or is not configured. */
    public Optional<TwitchActivity> byIgdbId(String igdbId, String language, FetchBudget budget) {
        if (!client.isConfigured()) {
            return Optional.empty();
        }
        FetchBudget once = FetchBudget.once(budget);
        Optional<TwitchCategory> category = categories.findFirstByIgdbId(igdbId).map(TwitchActivityService::toCategory);
        if (category.isEmpty()) {
            category = cache.get(EnrichmentSource.TWITCH_CATEGORY, "igdb:" + igdbId, TwitchCategory.class, once,
                    () -> client.categoryByIgdbId(igdbId).map(c -> {
                        cache.put(EnrichmentSource.TWITCH_CATEGORY, "cat:" + c.id(), c);
                        return c;
                    }));
        }
        return category.flatMap(c -> activity(c, language, once));
    }

    /** The activity of a Twitch category id; empty when Twitch has none or is not configured. */
    public Optional<TwitchActivity> byCategoryId(String categoryId, String language, FetchBudget budget) {
        if (!client.isConfigured()) {
            return Optional.empty();
        }
        FetchBudget once = FetchBudget.once(budget);
        Optional<TwitchCategory> category = categories.findById(categoryId).map(TwitchActivityService::toCategory);
        if (category.isEmpty()) {
            category = cache.get(EnrichmentSource.TWITCH_CATEGORY, "cat:" + categoryId, TwitchCategory.class, once,
                    () -> client.categoryById(categoryId));
        }
        return category.flatMap(c -> activity(c, language, once));
    }

    /**
     * Empty when the lives are unavailable (source disabled, fetch failed with nothing cached): a zero is only
     * a fetched zero. Missing clips are secondary and leave {@code topClips} empty.
     */
    private Optional<TwitchActivity> activity(TwitchCategory category, String language, FetchBudget budget) {
        String lang = language == null || language.isBlank() ? null : language;
        return cache.get(EnrichmentSource.TWITCH_LIVES,
                        "live:" + category.id() + ":" + (lang == null ? "all" : lang), TwitchLives.class, budget,
                        () -> Optional.of(client.lives(category.id(), lang)))
                .map(lives -> {
                    List<TwitchClips.Clip> clips = cache.get(EnrichmentSource.TWITCH_CLIPS,
                                    "clips:" + category.id(), TwitchClips.class, budget,
                                    () -> Optional.of(client.clips(category.id(),
                                            clock.instant().minus(CLIPS_WINDOW))))
                            .map(TwitchClips::top).orElse(List.of());
                    return new TwitchActivity(category.id(), category.name(), lives.liveCount(), lives.viewers(),
                            lives.approximate(), lives.top(), clips);
                });
    }

    private static TwitchCategory toCategory(TwitchCategoryCache row) {
        return new TwitchCategory(row.getId(), row.getName());
    }
}
