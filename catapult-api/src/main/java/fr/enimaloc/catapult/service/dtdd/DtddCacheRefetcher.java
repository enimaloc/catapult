package fr.enimaloc.catapult.service.dtdd;

import fr.enimaloc.catapult.domain.dtdd.DtddGameCache;
import fr.enimaloc.catapult.domain.dtdd.DtddSearchCache;
import fr.enimaloc.catapult.domain.dtdd.DtddTopicsCache;
import fr.enimaloc.catapult.getter.dtdd.DtddApiClient;
import fr.enimaloc.catapult.getter.dtdd.DtddApiClient.DtddSearchResult;
import fr.enimaloc.catapult.repository.dtdd.DtddGameCacheRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddSearchCacheRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddTopicsCacheRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;

/**
 * Fetches one cached DTDD entry again and saves what DTDD answers, as the cache's own readers would have: a title's
 * topics, a title (its item), a search (and the titles it finds). Nothing is changed when DTDD answers nothing.
 */
@Component
public class DtddCacheRefetcher {

    private final DtddTopicsCacheRepository topicsRepo;
    private final DtddGameCacheRepository gameRepo;
    private final DtddSearchCacheRepository searchRepo;
    private final Clock clock;

    @Autowired
    public DtddCacheRefetcher(DtddTopicsCacheRepository topicsRepo, DtddGameCacheRepository gameRepo,
                              DtddSearchCacheRepository searchRepo) {
        this(topicsRepo, gameRepo, searchRepo, Clock.systemUTC());
    }

    DtddCacheRefetcher(DtddTopicsCacheRepository topicsRepo, DtddGameCacheRepository gameRepo,
                       DtddSearchCacheRepository searchRepo, Clock clock) {
        this.topicsRepo = topicsRepo;
        this.gameRepo = gameRepo;
        this.searchRepo = searchRepo;
        this.clock = clock;
    }

    /**
     * Refetches the {@code kind} entry {@code key} (a title's ID, or a search's query) through {@code client}.
     *
     * @return whether DTDD answered and the entry was saved; false when it answered nothing (unknown title, no key
     *         usable, DTDD unavailable)
     */
    public boolean refetch(DtddApiClient client, DtddCacheKind kind, String key) {
        Instant now = clock.instant();
        return switch (kind) {
            case TOPICS -> client.fetchTopics(id(key)).map(topics -> {
                topicsRepo.save(new DtddTopicsCache(id(key), topics.yesTopics(), topics.noTopics(),
                        topics.mostlyTopics(), now));
                return true;
            }).orElse(false);
            case GAMES -> client.item(id(key)).map(item -> {
                DtddGameCache game = gameRepo.findById(id(key)).orElseGet(() -> {
                    DtddGameCache created = new DtddGameCache();
                    created.setDtddId(id(key));
                    created.setSlug("https://www.doesthedogdie.com/media/" + id(key));
                    return created;
                });
                if (item.name() != null) game.setName(item.name());
                if (item.itemTypeName() != null) game.setMediaType(item.itemTypeName());
                // The search gives a poster URL; the item only a full one is taken from
                if (item.posterImage() != null && item.posterImage().startsWith("http")) {
                    game.setPosterUrl(item.posterImage());
                }
                if (game.getName() == null) return false;
                game.setFetchedAt(now);
                gameRepo.save(game);
                return true;
            }).orElse(false);
            // As the searches were made: the video games only
            case SEARCHES -> client.search(key).map(results -> {
                for (DtddSearchResult r : results) {
                    gameRepo.save(new DtddGameCache(r.dtddId(), r.name(), r.slug(), r.mediaType(), r.posterUrl(), now));
                }
                searchRepo.save(new DtddSearchCache(key, results.stream().map(DtddSearchResult::dtddId).toList(), now));
                return true;
            }).orElse(false);
        };
    }

    private static long id(String key) {
        try {
            return Long.parseLong(key.trim());
        } catch (NumberFormatException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a DTDD title ID");
        }
    }
}
