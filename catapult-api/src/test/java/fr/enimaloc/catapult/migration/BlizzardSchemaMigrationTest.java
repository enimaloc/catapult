package fr.enimaloc.catapult.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** V129's cascades, which the H2 repository test never executes. */
@Testcontainers
class BlizzardSchemaMigrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_account (id, twitch_id, twitch_username) VALUES (?, ?, 'streamer')",
                id, "blizzard-test-" + id);
        return id;
    }

    private void account(UUID user) {
        jdbc.update("INSERT INTO blizzard_account (user_id, region, updated_at) VALUES (?, 'eu', now())", user);
    }

    private void character(UUID user, long id) {
        jdbc.update("INSERT INTO blizzard_wow_character (user_id, region, character_id, realm_slug, realm_name, "
                + "name, display_name, level, class_name, faction) "
                + "VALUES (?, 'eu', ?, 'archimonde', 'Archimonde', 'alpha', 'Alpha', 80, 'Mage', 'Alliance')",
                user, id);
    }

    private int count(String table, UUID user) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id = ?", Integer.class, user);
    }

    @Test
    void deletingTheMembersAccountDeletesTheRowAndItsCharacters() {
        UUID user = user();
        account(user);
        character(user, 1);
        character(user, 2);

        jdbc.update("DELETE FROM user_account WHERE id = ?", user);

        assertThat(count("blizzard_account", user)).isZero();
        assertThat(count("blizzard_wow_character", user)).isZero();
    }

    @Test
    void deletingTheRowDeletesItsCharacters() {
        UUID user = user();
        account(user);
        character(user, 1);

        jdbc.update("DELETE FROM blizzard_account WHERE user_id = ?", user);

        assertThat(count("blizzard_wow_character", user)).isZero();
    }

    @Test
    void aCharacterNeedsTheRowAndIsUnique() {
        UUID user = user();
        assertThatThrownBy(() -> character(user, 1)).isInstanceOf(DataIntegrityViolationException.class);

        account(user);
        character(user, 1);
        assertThatThrownBy(() -> character(user, 1)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
