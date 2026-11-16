package fr.enimaloc.catapult.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Runs V118 on a real Postgres: auto bindings of Xbox apps go, everything else stays. */
@Testcontainers
class XboxAppBindingsMigrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    private static void insertBinding(JdbcTemplate jdbc, UUID user, String type, String name, String status) {
        jdbc.update("INSERT INTO game_binding (user_id, source_id, source_type, source_name, status) "
                + "VALUES (?, 'x', ?, ?, ?)", user, type, name, status);
    }

    @Test
    void dropsAutoXboxAppBindingsOnly() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).target("117").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO user_account (id, twitch_id, twitch_username, created_at, updated_at) "
                + "VALUES (?, 't1', 'login', now(), now())", user);
        insertBinding(jdbc, user, "XBOX", "Microsoft Store", "AUTO");
        insertBinding(jdbc, user, "XBOX", "Twitch", "AUTO");
        insertBinding(jdbc, user, "XBOX", "Twitch", "MANUAL");
        insertBinding(jdbc, user, "XBOX", "Halo Infinite", "AUTO");
        insertBinding(jdbc, user, "STEAM", "Twitch", "AUTO");

        Flyway.configure().dataSource(dataSource).load().migrate();

        List<String> remaining = jdbc.queryForList(
                "SELECT source_type || '/' || source_name || '/' || status FROM game_binding ORDER BY 1", String.class);
        assertThat(remaining).containsExactlyInAnyOrder("XBOX/Twitch/MANUAL", "XBOX/Halo Infinite/AUTO", "STEAM/Twitch/AUTO");
    }
}
