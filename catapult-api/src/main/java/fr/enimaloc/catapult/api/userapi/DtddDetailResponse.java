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
     * A topic's votes as the public API answers them; {@code severity}: how severe it is on the title, null when the
     * topic has no levels. Named as DTDD's, so the OpenAPI schema keeps its name.
     */
    public record DtddTopicItemStat(long topicItemId, long yesSum, long noSum, long numComments, long topicId,
                                    String topicName, long itemId, DtddSeverity severity) {

        static DtddTopicItemStat[] of(DtddApiClient.DtddTopicItemStat[] stats) {
            if (stats == null) return null;
            return Arrays.stream(stats)
                    .map(s -> new DtddTopicItemStat(s.topicItemId(), s.yesSum(), s.noSum(), s.numComments(),
                            s.topicId(), s.topicName(), s.itemId(), DtddSeverity.of(s.severity())))
                    .toArray(DtddTopicItemStat[]::new);
        }
    }

    /**
     * How severe a topic is on the title, as DTDD's members voted it: {@code levels} its labels, mildest first;
     * {@code counts} and {@code percents} the votes for each, by position; {@code total} the level votes;
     * {@code verified} the level DTDD's reviewers gave (1-based), null when they gave none.
     */
    public record DtddSeverity(List<String> levels, List<Integer> counts, List<Integer> percents, int total,
                               Integer verified) {

        static DtddSeverity of(DtddApiClient.DtddSeverity severity) {
            if (severity == null) return null;
            return new DtddSeverity(severity.levels(), severity.counts(), severity.percents(), severity.total(),
                    severity.verified());
        }
    }
}
