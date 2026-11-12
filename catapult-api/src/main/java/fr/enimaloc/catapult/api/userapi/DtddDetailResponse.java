package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.getter.dtdd.DtddApiClient;

import java.util.Arrays;
import java.util.List;

public record DtddDetailResponse(String url, List<String> yesTopics, List<String> noTopics,
                                 List<String> mostlyTopics, String overview, String[] genres,
                                 long releaseYear, String itemTypeName, long tmdbId, String imdbId,
                                 String posterImage, String backgroundImage,
                                 DtddTopicItemStat[] topicItemStats) {

    /**
     * A topic's votes as the public API answers them: frozen (FrozenRouteContractTest), so what DTDD adds to its
     * own (its severity) stays out of it. Named as DTDD's, so the OpenAPI schema keeps its name.
     */
    public record DtddTopicItemStat(long topicItemId, long yesSum, long noSum, long numComments, long topicId,
                                    String topicName, long itemId) {

        static DtddTopicItemStat[] of(DtddApiClient.DtddTopicItemStat[] stats) {
            if (stats == null) return null;
            return Arrays.stream(stats)
                    .map(s -> new DtddTopicItemStat(s.topicItemId(), s.yesSum(), s.noSum(), s.numComments(),
                            s.topicId(), s.topicName(), s.itemId()))
                    .toArray(DtddTopicItemStat[]::new);
        }
    }
}
