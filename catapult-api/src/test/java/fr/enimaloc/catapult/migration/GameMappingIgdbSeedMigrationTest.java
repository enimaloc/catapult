package fr.enimaloc.catapult.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;

/** Reads V110 as text: the seed needs a database to run, the content it promises does not. */
class GameMappingIgdbSeedMigrationTest {

    private static String sql() throws IOException {
        return new ClassPathResource("db/migration/V110__game_mapping_igdb.sql")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "WoW|18122|World of Warcraft|123",
            "WTCG|138585|Hearthstone|1279",
            "Pro|515025|Overwatch|125174",
            "Fen|515024|Diablo IV|125165",
            "D3|313558|Diablo III|120",
            "OSI|1788326126|Diablo II: Resurrected|142803",
            "S2|490422|StarCraft II|239",
            "Hero|32959|Heroes of the Storm|7313",
            "W3|12924|Warcraft III|111650"})
    void seedInsertsTheBlizzardTitle(String fourcc, String twitchId, String twitchName, String igdbId)
            throws IOException {
        assertThat(sql()).contains("('BATTLENET', '" + fourcc + "', '" + twitchId + "', '" + twitchName + "', '"
                + igdbId + "')");
    }

    @Test
    void seedInsertsTheNineBlizzardTitles() throws IOException {
        assertThat(sql().split("\\('BATTLENET'", -1)).hasSize(10);
    }

    @Test
    void seedNeverOverwritesAnExistingMapping() throws IOException {
        assertThat(sql().strip()).endsWith("ON CONFLICT (source_type, source_id) DO NOTHING;");
    }

    @Test
    void theColumnIsNullable() throws IOException {
        assertThat(sql()).contains("ALTER TABLE game_mapping ADD COLUMN igdb_id TEXT;");
    }
}
