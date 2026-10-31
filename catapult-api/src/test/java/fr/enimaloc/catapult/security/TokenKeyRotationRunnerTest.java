package fr.enimaloc.catapult.security;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** On the real schema, so that the names in {@link TokenKeyRotationRunner#SECRETS} are checked against it. */
@Testcontainers
class TokenKeyRotationRunnerTest {
    private static final String OLD_KEY = "old-encryption-key-32-bytes-long!";
    private static final String NEW_KEY = "new-encryption-key-32-bytes-long!";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    private static JdbcTemplate jdbc;

    private final TokenEncryptionService old = new TokenEncryptionService(OLD_KEY);
    private final TokenEncryptionService current = new TokenEncryptionService(NEW_KEY);
    private final TokenEncryptionService rotating = new TokenEncryptionService(NEW_KEY, OLD_KEY);
    private final TokenKeyRotationRunner runner = new TokenKeyRotationRunner(rotating, jdbc);

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM oauth_token");
        jdbc.update("DELETE FROM user_account");
    }

    private UUID user(String steamToken) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_account (id, status, steam_personal_token, created_at, updated_at) "
                + "VALUES (?, 'ACTIVE', ?, now(), now())", id, steamToken);
        return id;
    }

    private String steamToken(UUID id) {
        return jdbc.queryForObject("SELECT steam_personal_token FROM user_account WHERE id = ?", String.class, id);
    }

    @Test
    void everyColumn_existsInTheSchema() {
        for (TokenKeyRotationRunner.Secret secret : TokenKeyRotationRunner.SECRETS) {
            assertThat(jdbc.queryForList("SELECT %s, %s FROM %s".formatted(secret.id(), secret.column(), secret.table())))
                    .isEmpty();
        }
    }

    @Test
    void thePreviousKeysValues_moveToTheCurrentKey_andOtherColumnsToo() {
        UUID user = user(old.encrypt("steam"));
        jdbc.update("INSERT INTO oauth_token (id, user_id, provider, access_token, refresh_token) VALUES (?, ?, 'TWITCH', ?, ?)",
                UUID.randomUUID(), user, old.encrypt("access"), old.encrypt("refresh"));

        runner.run(null);

        assertThat(current.decrypt(steamToken(user))).isEqualTo("steam");
        Map<String, Object> token = jdbc.queryForMap("SELECT access_token, refresh_token FROM oauth_token");
        assertThat(current.decrypt((String) token.get("access_token"))).isEqualTo("access");
        assertThat(current.decrypt((String) token.get("refresh_token"))).isEqualTo("refresh");
    }

    @Test
    void running_twice_changesNothingMore() {
        UUID user = user(old.encrypt("steam"));
        runner.run(null);
        String once = steamToken(user);

        runner.run(null);

        assertThat(steamToken(user)).isEqualTo(once);
    }

    @Test
    void whatNeitherKeyReads_isLeftAsIs_andTheOthersStillMove() {
        UUID unreadable = user("not encrypted");
        UUID readable = user(old.encrypt("steam"));

        runner.run(null);

        assertThat(steamToken(unreadable)).isEqualTo("not encrypted");
        assertThat(current.decrypt(steamToken(readable))).isEqualTo("steam");
    }

    @Test
    void aValueRefreshedBetweenTheReadAndTheWrite_isNotOverwritten() {
        UUID user = user(old.encrypt("stale"));
        String refreshed = current.encrypt("refreshed");
        JdbcTemplate refreshingAfterTheRead = new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public List<Map<String, Object>> queryForList(String sql) {
                List<Map<String, Object>> rows = super.queryForList(sql);
                jdbc.update("UPDATE user_account SET steam_personal_token = ? WHERE id = ?", refreshed, user);
                return rows;
            }
        };

        new TokenKeyRotationRunner(rotating, refreshingAfterTheRead).run(null);

        assertThat(steamToken(user)).isEqualTo(refreshed);
    }

    @Test
    void withoutAPreviousKey_itDoesNothing() {
        JdbcTemplate untouched = mock(JdbcTemplate.class);

        new TokenKeyRotationRunner(current, untouched).run(null);

        verifyNoInteractions(untouched);
    }
}
