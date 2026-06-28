package fr.enimaloc.catapult.getter;

import org.jetbrains.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@Profile("mock")
public class MockDtddApiClient implements DtddApiClient {

    private static final Map<String, List<DtddSearchResult>> FIXTURES_SEARCH = Map.of(
        "stardew valley", List.of(
            new DtddSearchResult(4521L, "Stardew Valley", "stardew-valley", "Video Game", null)
        ),
        "doki doki literature club", List.of(
            new DtddSearchResult(1287L, "Doki Doki Literature Club", "doki-doki-literature-club", "Video Game", null)
        ),
        "doki", List.of(
            new DtddSearchResult(1287L, "Doki Doki Literature Club", "doki-doki-literature-club", "Video Game", null),
            new DtddSearchResult(9999L, "Doki (other thing)", "doki-other", "Movie", null)
        )
    );

    private static final Map<Long, DtddTopics> FIXTURES_TOPICS = Map.of(
        4521L, new DtddTopics(
            List.of("An animal dies"),
            List.of("A child dies", "Sexual assault"),
            List.of()
        ),
        1287L, new DtddTopics(
            List.of("Suicide", "Self-harm", "Mental illness", "Flashing lights"),
            List.of(),
            List.of("A child dies")
        )
    );

    @Override
    public Optional<List<DtddSearchResult>> search(String query, @Nullable String mediaType) {
        return Optional.of(FIXTURES_SEARCH.getOrDefault(query.toLowerCase(), List.of()));
    }

    @Override
    public Optional<DtddTopics> fetchTopics(long dtddId) {
        return Optional.ofNullable(FIXTURES_TOPICS.get(dtddId));
    }

    @Override
    public Optional<DtddItem> item(long dtddId) {
        return Optional.empty();
    }
}
