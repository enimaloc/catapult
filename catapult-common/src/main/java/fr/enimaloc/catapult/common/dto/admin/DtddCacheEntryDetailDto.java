package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;

/**
 * Everything the DTDD caches keep about one entry. A title ({@code dtddId}): its cached record ({@code game}, null
 * when not cached), its topics in full ({@code topics}, null when not cached) and the cached searches that found it.
 * A search ({@code query}): itself ({@code searches}, its only element) and the titles it found ({@code titles}),
 * each as cached ({@code name} null when not) with whether its topics are cached too.
 */
public record DtddCacheEntryDetailDto(Long dtddId, String query, DtddGameCacheEntryDto game,
                                      DtddTopicsCacheEntryDto topics, List<DtddSearchCacheEntryDto> searches,
                                      List<FoundTitle> titles) {

    /** A title a search found: its ID, its cached name (null when not cached), whether its topics are cached. */
    public record FoundTitle(long dtddId, String name, boolean topicsCached) {}
}
