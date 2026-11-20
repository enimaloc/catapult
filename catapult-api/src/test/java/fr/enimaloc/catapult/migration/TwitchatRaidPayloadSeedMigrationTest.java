package fr.enimaloc.catapult.migration;

import fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType;
import fr.enimaloc.catapult.service.notification.TwitchatEventDefaults;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs V122 on a real Postgres: only initialized accounts without a raid payload get the default one, once. */
@Testcontainers
class TwitchatRaidPayloadSeedMigrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    private static UUID account(JdbcTemplate jdbc, String login, boolean initialized) {
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO user_account (id, twitch_id, twitch_username, created_at, updated_at) "
                + "VALUES (?, ?, ?, now(), now())", user, "raid-" + login, login);
        jdbc.update("INSERT INTO twitchat_widget_settings (user_id, enabled, event_actions_initialized) "
                + "VALUES (?, TRUE, ?)", user, initialized);
        return user;
    }

    private static List<Map<String, Object>> raidRows(JdbcTemplate jdbc, UUID user) {
        return jdbc.queryForList("SELECT position, action, mode, data_json, enabled FROM twitchat_event_action "
                + "WHERE user_id = ? AND event_type = 'RAIDED_BY_MEMBER'", user);
    }

    @Test
    void seedsInitializedAccountsWithoutARaidPayloadOnly() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).target("121").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID initialized = account(jdbc, "initialized", true);
        UUID notInitialized = account(jdbc, "not-initialized", false);
        UUID alreadySeeded = account(jdbc, "already-seeded", true);
        jdbc.update("INSERT INTO twitchat_event_action (id, user_id, event_type, position, action, mode, data_json, "
                + "enabled, created_at, updated_at) VALUES (?, ?, 'RAIDED_BY_MEMBER', 0, 'CUSTOM_CHAT_MESSAGE', "
                + "'FORM', '{\"message\":\"mine\"}', FALSE, now(), now())", UUID.randomUUID(), alreadySeeded);

        Flyway.configure().dataSource(dataSource).load().migrate();

        String expected = JsonMapper.builder().build().writeValueAsString(
                TwitchatEventDefaults.chatMessage(TwitchatNotificationEventType.RAIDED_BY_MEMBER));
        assertThat(raidRows(jdbc, initialized)).singleElement().satisfies(row -> {
            assertThat(row.get("position")).isEqualTo(0);
            assertThat(row.get("action")).isEqualTo("CUSTOM_CHAT_MESSAGE");
            assertThat(row.get("mode")).isEqualTo("FORM");
            assertThat(row.get("data_json")).isEqualTo(expected);
            assertThat(row.get("enabled")).isEqualTo(true);
        });
        assertThat(raidRows(jdbc, notInitialized)).isEmpty();
        assertThat(raidRows(jdbc, alreadySeeded)).singleElement().satisfies(row -> {
            assertThat(row.get("data_json")).isEqualTo("{\"message\":\"mine\"}");
            assertThat(row.get("enabled")).isEqualTo(false);
        });
    }
}
