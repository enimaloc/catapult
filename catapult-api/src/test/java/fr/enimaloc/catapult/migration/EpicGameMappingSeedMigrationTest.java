package fr.enimaloc.catapult.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Runs V117 on a real Postgres: the seed lands, and an admin's own Fall Guys mapping must survive it. */
@Testcontainers
class EpicGameMappingSeedMigrationTest {

    private static final String FALL_GUYS = "d42d7101bb7a41c09a810ee418b7d3d0";

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    private static DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static List<Map<String, Object>> epicRows(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT source_id, twitch_game_id, twitch_game_name, igdb_id FROM game_mapping "
                + "WHERE source_type = 'EPIC'");
    }

    @Test
    void seedsFallGuysOnAFreshDatabase() {
        DriverManagerDataSource dataSource = dataSource();
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();

        Flyway.configure().dataSource(dataSource).load().migrate();

        List<Map<String, Object>> rows = epicRows(new JdbcTemplate(dataSource));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .containsEntry("source_id", FALL_GUYS)
                .containsEntry("twitch_game_id", "512980")
                .containsEntry("twitch_game_name", "Fall Guys")
                .containsEntry("igdb_id", "119313");
    }

    @Test
    void keepsAnExistingAdminMapping() {
        DriverManagerDataSource dataSource = dataSource();
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).target("116").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO game_mapping (source_type, source_id, twitch_game_id, twitch_game_name) "
                + "VALUES ('EPIC', ?, '999', 'Admin Fall Guys')", FALL_GUYS);

        Flyway.configure().dataSource(dataSource).load().migrate();

        List<Map<String, Object>> rows = epicRows(jdbc);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .containsEntry("twitch_game_id", "999")
                .containsEntry("twitch_game_name", "Admin Fall Guys")
                .containsEntry("igdb_id", null);
    }
}
