package fr.enimaloc.catapult.service.enrichment.steamdeck;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The verdict reads in lower case on the detail route, as in the aggregate, and old cache entries still read. */
class SteamDeckCompatibilityTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void category_serializedInLowerCase_andReadBack() {
        SteamDeckCompatibility verified = new SteamDeckCompatibility(SteamDeckCompatibility.Category.VERIFIED,
                List.of(new SteamDeckCompatibility.TestedItem("4", "#Token")));

        String json = mapper.writeValueAsString(verified);

        assertThat(json).contains("\"category\":\"verified\"");
        assertThat(mapper.readValue(json, SteamDeckCompatibility.class)).isEqualTo(verified);
    }

    @Test
    void upperCaseCacheEntry_stillReads() {
        assertThat(mapper.readValue("{\"category\":\"PLAYABLE\",\"items\":[]}", SteamDeckCompatibility.class)
                .category()).isEqualTo(SteamDeckCompatibility.Category.PLAYABLE);
    }
}
