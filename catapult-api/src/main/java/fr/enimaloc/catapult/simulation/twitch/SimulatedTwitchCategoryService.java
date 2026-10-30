package fr.enimaloc.catapult.simulation.twitch;

import fr.enimaloc.catapult.service.twitch.TwitchCategory;
import fr.enimaloc.catapult.service.twitch.TwitchCategoryService;
import fr.enimaloc.catapult.service.twitch.TwitchCategoryServiceImpl;
import fr.enimaloc.catapult.simulation.SimulatedCallRunner;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Locale;

/**
 * The Twitch category search with the app token, in the global {@code twitch.app.category-search} mode. A failure
 * answers an empty list, as the real search does. The simulated answer comes from a small catalog of well-known
 * categories, or, when none matches, a single category named after the query, with an id stable for that name: any
 * game resolves while simulated.
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("catapult.simulation.enabled")
public class SimulatedTwitchCategoryService implements TwitchCategoryService {

    private static final List<TwitchCategory> CATALOG = List.of(
            new TwitchCategory("509658", "Just Chatting", ""),
            new TwitchCategory("26936", "Music", ""),
            new TwitchCategory("21779", "League of Legends", ""),
            new TwitchCategory("516575", "VALORANT", ""),
            new TwitchCategory("1469308723", "Science & Technology", ""),
            new TwitchCategory("32982", "Grand Theft Auto V", ""),
            new TwitchCategory("27471", "Minecraft", ""),
            new TwitchCategory("509659", "ASMR", ""));

    private final ObjectProvider<TwitchCategoryServiceImpl> real;
    private final SimulatedCallRunner runner;

    @Override
    public List<TwitchCategory> searchCategories(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            return runner.run(null, SimulatedCalls.TWITCH_APP_CATEGORY_SEARCH, "GET /search/categories " + query,
                    () -> real().searchCategories(query), () -> simulatedSearch(query),
                    SimulatedCallRunner::httpError);
        } catch (RestClientException e) {
            log.warn("searchCategories '{}' — live search failed: {}", query, e.getMessage());
            return List.of();
        }
    }

    /** The category Twitch would hold for exactly {@code name}: a catalog one, else one made up for it. */
    static TwitchCategory categoryNamed(String name) {
        return CATALOG.stream()
                .filter(category -> category.name().equalsIgnoreCase(name.strip()))
                .findFirst()
                .orElseGet(() -> new TwitchCategory(madeUpId(name), name.strip(), ""));
    }

    private static List<TwitchCategory> simulatedSearch(String query) {
        String q = query.strip().toLowerCase(Locale.ROOT);
        List<TwitchCategory> matches = CATALOG.stream()
                .filter(category -> category.name().toLowerCase(Locale.ROOT).contains(q))
                .toList();
        return matches.isEmpty() ? List.of(categoryNamed(query)) : matches;
    }

    /** A numeric id, as Twitch's are, the same for every spelling of the name that only differs in case. */
    private static String madeUpId(String name) {
        return String.valueOf(2_000_000_000L + Math.floorMod(name.strip().toLowerCase(Locale.ROOT).hashCode(),
                1_000_000_000));
    }

    private TwitchCategoryServiceImpl real() {
        TwitchCategoryServiceImpl impl = real.getIfAvailable();
        if (impl == null) {
            throw new IllegalStateException(
                    "The real Twitch category search is not available");
        }
        return impl;
    }
}
