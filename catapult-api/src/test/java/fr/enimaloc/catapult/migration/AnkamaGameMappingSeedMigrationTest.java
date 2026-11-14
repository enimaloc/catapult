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

/** Runs V114 on a real Postgres: an admin's Wakfu mapping exists before it, and must survive it. */
@Testcontainers
class AnkamaGameMappingSeedMigrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    private static DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Test
    void seedsTheFourGamesAndKeepsAnExistingAdminMapping() {
        DriverManagerDataSource dataSource = dataSource();
        Flyway.configure().dataSource(dataSource).target("113").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO game_mapping (source_type, source_id, twitch_game_id, twitch_game_name) "
                + "VALUES ('ANKAMA', '3', '999', 'Admin Wakfu')");

        Flyway.configure().dataSource(dataSource).load().migrate();

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT source_id, twitch_game_id, twitch_game_name, igdb_id FROM game_mapping "
                        + "WHERE source_type = 'ANKAMA' ORDER BY source_id::int");
        assertThat(rows).extracting(r -> r.get("source_id")).containsExactly("1", "3", "17", "22");
        assertThat(rows).extracting(r -> r.get("twitch_game_id")).containsExactly("20596", "999", "493754", "511817");
        assertThat(rows).extracting(r -> r.get("twitch_game_name"))
                .containsExactly("Dofus", "Admin Wakfu", "Krosmaga", "Waven");
        assertThat(rows).extracting(r -> r.get("igdb_id")).containsExactly("18337", null, "29097", "109536");
    }
}
