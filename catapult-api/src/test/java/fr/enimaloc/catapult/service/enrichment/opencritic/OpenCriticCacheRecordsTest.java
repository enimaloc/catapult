package fr.enimaloc.catapult.service.enrichment.opencritic;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** What EnrichmentCache stores must come back equal through its Jackson 3 mapper, or every lookup would refetch. */
class OpenCriticCacheRecordsTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void refRoundTrips() {
        OpenCriticRef ref = new OpenCriticRef(463);

        assertThat(mapper.readValue(mapper.writeValueAsString(ref), OpenCriticRef.class)).isEqualTo(ref);
    }

    @Test
    void gameRoundTrips() {
        OpenCriticGame game =
                new OpenCriticGame(463, "Hades", 93, 98, "Mighty", 78, "https://opencritic.com/game/463/hades");
        OpenCriticGame unrated = new OpenCriticGame(9001, "New", null, null, null, 0, "https://opencritic.com/game/9001/new");

        assertThat(mapper.readValue(mapper.writeValueAsString(game), OpenCriticGame.class)).isEqualTo(game);
        assertThat(mapper.readValue(mapper.writeValueAsString(unrated), OpenCriticGame.class)).isEqualTo(unrated);
    }
}
