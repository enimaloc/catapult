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

    record DtddTopicItemStat(long topicItemId, long yesSum, long noSum, long numComments, long topicId, String topicName, long itemId) {}
}
