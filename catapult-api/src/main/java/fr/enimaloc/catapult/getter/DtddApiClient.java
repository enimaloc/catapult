package fr.enimaloc.catapult.getter;

import java.util.List;
import java.util.Optional;

public interface DtddApiClient {

    /** Search par nom. Liste peut être vide. Renvoie Optional.empty() si toutes les clés sont rate-limit. */
    Optional<List<DtddSearchResult>> search(String query);

    /** Fetch topics par dtddId. Optional.empty() si 404 ou rate-limit total. */
    Optional<DtddTopics> fetchTopics(long dtddId);

    record DtddSearchResult(long dtddId, String name, String slug, String mediaType, String posterUrl) {}

    record DtddTopics(List<String> yesTopics, List<String> noTopics, List<String> mostlyTopics) {}
}
