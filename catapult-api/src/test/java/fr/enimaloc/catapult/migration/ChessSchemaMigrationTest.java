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

/** V134's cascade and its "a site is whole or absent" checks, which the H2 tests never execute. */
@Testcontainers
class ChessSchemaMigrationTest {

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
                id, "chess-test-" + id);
        return id;
    }

    private int count(UUID user) {
        return jdbc.queryForObject("SELECT count(*) FROM chess_account WHERE user_id = ?", Integer.class, user);
    }

    @Test
    void deletingTheMembersAccountDeletesTheRow() {
        UUID user = user();
        jdbc.update("INSERT INTO chess_account (user_id, chesscom_username, chesscom_player_id, lichess_id, "
                + "lichess_username, updated_at) VALUES (?, 'hikaru', 15448422, 'drnykterstein', 'DrNykterstein', "
                + "now())", user);

        jdbc.update("DELETE FROM user_account WHERE id = ?", user);

        assertThat(count(user)).isZero();
    }

    @Test
    void eachSiteIsWholeOrAbsent() {
        UUID user = user();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO chess_account (user_id, chesscom_username, updated_at) "
                + "VALUES (?, 'hikaru', now())", user)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO chess_account (user_id, lichess_id, updated_at) "
                + "VALUES (?, 'drnykterstein', now())", user)).isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("INSERT INTO chess_account (user_id, lichess_id, lichess_username, updated_at) "
                + "VALUES (?, 'drnykterstein', 'DrNykterstein', now())", user);
        assertThat(count(user)).isOne();
    }
}
