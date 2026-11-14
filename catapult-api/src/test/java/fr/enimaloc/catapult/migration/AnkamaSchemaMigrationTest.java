package fr.enimaloc.catapult.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** V113's cascades and partial unique indexes, which the H2 repository test never executes. */
@Testcontainers
class AnkamaSchemaMigrationTest {

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

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM ankama_friend_link");
        jdbc.update("DELETE FROM ankama_service_account");
        jdbc.update("DELETE FROM user_account WHERE twitch_id LIKE 'ankama-test-%'");
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_account (id, twitch_id, twitch_username) VALUES (?, ?, 'streamer')",
                id, "ankama-test-" + id);
        return id;
    }

    private UUID serviceAccount() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO ankama_service_account (id, api_key_enc, refresh_token_enc, ankama_account_id, "
                + "nickname, tag, device_id, hm1, last_refresh_at) "
                + "VALUES (?, 'k', 'r', ?, 'bot', '0001', 'device', 'hm1', now())",
                id, Math.abs(id.getMostSignificantBits()));
        return id;
    }

    private UUID link(UUID user, UUID serviceAccount, String name, String tag, Long ankamaUserId, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO ankama_friend_link (id, user_id, service_account_id, requested_name, "
                + "requested_tag, ankama_user_id, status) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, user, serviceAccount, name, tag, ankamaUserId, status);
        return id;
    }

    private int links() {
        return jdbc.queryForObject("SELECT count(*) FROM ankama_friend_link", Integer.class);
    }

    @Test
    void deletingTheServiceAccountDeletesItsLinks() {
        UUID account = serviceAccount();
        link(user(), account, "Joe", "1234", null, "PENDING");

        jdbc.update("DELETE FROM ankama_service_account WHERE id = ?", account);

        assertThat(links()).isZero();
    }

    @Test
    void deletingTheUserDeletesItsLink() {
        UUID user = user();
        link(user, serviceAccount(), "Joe", "1234", null, "PENDING");

        jdbc.update("DELETE FROM user_account WHERE id = ?", user);

        assertThat(links()).isZero();
    }

    @Test
    void twoActiveLinksCannotShareAnIdentityIgnoringCase() {
        UUID account = serviceAccount();
        link(user(), account, "Joe", "1234", null, "PENDING");

        assertThatThrownBy(() -> link(user(), account, "JOE", "1234", null, "ACCEPTED"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void aRemovedLinkDoesNotBlockANewOneForTheSameIdentity() {
        UUID account = serviceAccount();
        link(user(), account, "Joe", "1234", null, "REMOVED");

        assertThatCode(() -> link(user(), account, "Joe", "1234", null, "PENDING")).doesNotThrowAnyException();
    }

    @Test
    void twoActiveLinksCannotShareAnAnkamaUserId() {
        UUID account = serviceAccount();
        link(user(), account, "Joe", "1234", 42L, "ACCEPTED");

        assertThatThrownBy(() -> link(user(), account, "Jane", "5678", 42L, "ACCEPTED"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void aRemovedLinkDoesNotBlockTheSameAnkamaUserId() {
        UUID account = serviceAccount();
        link(user(), account, "Joe", "1234", 42L, "REMOVED");

        assertThatCode(() -> link(user(), account, "Jane", "5678", 42L, "ACCEPTED")).doesNotThrowAnyException();
    }
}
