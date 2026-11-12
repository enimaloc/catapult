package fr.enimaloc.catapult.getter.dtdd;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

public interface DtddApiClient {

    /** Searches by name. The list may be empty. Returns Optional.empty() if all keys are rate-limited. */
    Optional<List<DtddSearchResult>> search(String query, @Nullable String mediaType);

    default Optional<List<DtddSearchResult>> search(String query) {
        return search(query, "Video Game");
    }

    /** Fetches topics by dtddId. Optional.empty() on a 404 or when every key is rate-limited. */
    Optional<DtddTopics> fetchTopics(long dtddId);

    Optional<DtddItem> item(long dtddId);

    record DtddSearchResult(long dtddId, String name, String slug, String mediaType, String posterUrl) {}

    record DtddTopics(List<String> yesTopics, List<String> noTopics, List<String> mostlyTopics) {}

    record DtddItem(long id, String name, String[] genres, long releaseYear, long itemTypeId, String itemTypeName,
                    long tmdbId, String imdbId, String backgroundImage, String posterImage, String overview,
                    DtddTopicItemStat[] topicItemStats) {}

    /** A topic's votes on a title; {@code severity}: its level votes, null when the topic has no levels. */
    record DtddTopicItemStat(long topicItemId, long yesSum, long noSum, long numComments, long topicId, String topicName,
                             long itemId, DtddSeverity severity) {}

    /**
     * How severe a topic is on a title, as DTDD's members voted it: {@code levels} the topic's labels, mildest first
     * ("Implied", "Depicted", "Excessive"…); {@code counts} and {@code percents} the votes for each, by position;
     * {@code total} the level votes; {@code verified} the level DTDD's reviewers gave (1-based, as a rating's
     * severity), null when they gave none.
     */
    record DtddSeverity(List<String> levels, List<Integer> counts, List<Integer> percents, int total,
                        Integer verified) {

        /** The reviewers' level as its label, empty when they gave none (or one out of {@code levels}). */
        public Optional<String> verifiedLevel() {
            if (verified == null || levels == null || verified < 1 || verified > levels.size()) {
                return Optional.empty();
            }
            return Optional.of(levels.get(verified - 1));
        }
    }
}
