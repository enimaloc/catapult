package fr.enimaloc.catapult.getter;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

public interface DtddApiClient {

    /** Search par nom. Liste peut être vide. Renvoie Optional.empty() si toutes les clés sont rate-limit. */
    Optional<List<DtddSearchResult>> search(String query, @Nullable String mediaType);

    default Optional<List<DtddSearchResult>> search(String query) {
        return search(query, "Video Game");
    }

    /** Fetch topics par dtddId. Optional.empty() si 404 ou rate-limit total. */
    Optional<DtddTopics> fetchTopics(long dtddId);

    Optional<DtddItem> item(long dtddId);

    record DtddSearchResult(long dtddId, String name, String slug, String mediaType, String posterUrl) {}

    record DtddTopics(List<String> yesTopics, List<String> noTopics, List<String> mostlyTopics) {}

    record DtddItem(long id, String name, String[] genres, long releaseYear, long itemTypeId, String itemTypeName,
                    long tmdbId, String imdbID, String backgroundImage, String posterImage, String overview,
                    DtddTopicItemStat[] topicItemStats) {}

    record DtddTopicItemStat(long topicItemId, long yesSum, long noSum, long numComments, long topicId, String topicName, long itemId) {}
}
