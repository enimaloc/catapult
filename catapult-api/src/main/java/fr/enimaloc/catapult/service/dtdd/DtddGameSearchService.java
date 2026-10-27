package fr.enimaloc.catapult.service.dtdd;

import fr.enimaloc.catapult.common.dto.channel.SearchResponse;
import fr.enimaloc.catapult.common.dto.channel.SearchResultDto;
import fr.enimaloc.catapult.domain.dtdd.DtddGameCache;
import fr.enimaloc.catapult.domain.dtdd.DtddSearchCache;
import fr.enimaloc.catapult.getter.dtdd.DtddApiClient;
import fr.enimaloc.catapult.getter.dtdd.DtddApiClient.DtddSearchResult;
import fr.enimaloc.catapult.repository.dtdd.DtddGameCacheRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddSearchCacheRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The DTDD game search the dashboard's mapping picker uses: the cached games first, then DTDD itself the
 * first time a query is seen (the answer is cached by query).
 */
@Service
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("dtdd.enabled")
public class DtddGameSearchService {

    private final DtddGameCacheRepository gameRepo;
    private final DtddSearchCacheRepository searchRepo;
    private final DtddApiClient apiClient;

    /** The games matching {@code q} (neither blank nor too long: the caller checks). */
    public SearchResponse search(String q) {
        String norm = q.toLowerCase(Locale.ROOT).trim();
        Map<Long, SearchResultDto> dedup = new LinkedHashMap<>();

        for (DtddGameCache g : gameRepo.searchByNameLike(norm, PageRequest.of(0, 20))) {
            dedup.put(g.getDtddId(),
                new SearchResultDto(g.getDtddId(), g.getName(), g.getMediaType(), g.getPosterUrl()));
        }

        Optional<DtddSearchCache> sc = searchRepo.findById(norm);
        if (sc.isEmpty()) {
            apiClient.search(q).ifPresent(remote -> {
                Instant now = Instant.now();
                for (DtddSearchResult r : remote) {
                    gameRepo.save(new DtddGameCache(r.dtddId(), r.name(), r.slug(), r.mediaType(), r.posterUrl(), now));
                    dedup.putIfAbsent(r.dtddId(),
                        new SearchResultDto(r.dtddId(), r.name(), r.mediaType(), r.posterUrl()));
                }
                searchRepo.save(new DtddSearchCache(norm,
                    remote.stream().map(DtddSearchResult::dtddId).toList(), now));
            });
        }

        return new SearchResponse(new ArrayList<>(dedup.values()));
    }
}
