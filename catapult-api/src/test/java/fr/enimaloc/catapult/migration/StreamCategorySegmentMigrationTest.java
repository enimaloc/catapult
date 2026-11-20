package fr.enimaloc.catapult.migration;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.stream.StreamSession;
import fr.enimaloc.catapult.service.stream.SessionTimelineService;
import fr.enimaloc.catapult.service.stream.StreamSessionService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * V121 on a real Postgres, whose partial unique index (one running period per member) the H2 timeline tests never
 * have: a pair change goes through {@link SessionTimelineService}, committed, so the close must reach the database
 * before the insert of the next period.
 */
@Testcontainers
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SessionTimelineService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StreamCategorySegmentMigrationTest {

    private static final Instant START = Instant.parse("2026-11-20T18:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).target("121").load().migrate();
    }

    @Autowired SessionTimelineService timeline;
    @Autowired DataSource dataSource;
    @MockitoBean StreamSessionService sessions;

    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM stream_category_segment");
        jdbc.update("DELETE FROM user_account WHERE twitch_id LIKE 'timeline-test-%'");
    }

    private UserAccount live(String streamId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_account (id, twitch_id, twitch_username, created_at, updated_at) "
                + "VALUES (?, ?, 'streamer', now(), now())", id, "timeline-test-" + id);
        UserAccount user = new UserAccount();
        user.setId(id);
        StreamSession session = new StreamSession();
        session.setUserId(id);
        session.setStreamId(streamId);
        session.setStartedAt(START);
        when(sessions.find(user)).thenReturn(Optional.of(session));
        return user;
    }

    private int running(UserAccount user) {
        return jdbc.queryForObject("SELECT count(*) FROM stream_category_segment "
                + "WHERE user_id = ? AND ended_at IS NULL", Integer.class, user.getId());
    }

    private int periods(UserAccount user) {
        return jdbc.queryForObject("SELECT count(*) FROM stream_category_segment WHERE user_id = ?",
                Integer.class, user.getId());
    }

    private void insertRunning(UUID user, String streamId) {
        jdbc.update("INSERT INTO stream_category_segment (id, user_id, stream_id, started_at) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), user, streamId, Timestamp.from(START));
    }

    @Test
    void pairChangesKeepOneRunningPeriod() {
        UserAccount user = live("stream-1");

        assertThatCode(() -> {
            timeline.observe(user, "1", "Factorio", "Factorio", START);
            timeline.observeCategory(user, "2", "Just Chatting", START.plus(Duration.ofHours(1)));
            timeline.observeGame(user, "MOLTYN", START.plus(Duration.ofHours(2)));
        }).doesNotThrowAnyException();

        assertThat(periods(user)).isEqualTo(3);
        assertThat(running(user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT category_id || '/' || game_name FROM stream_category_segment "
                + "WHERE user_id = ? AND ended_at IS NULL", String.class, user.getId())).isEqualTo("2/MOLTYN");
    }

    @Test
    void theOpenIndexRefusesASecondRunningPeriod() {
        UUID user = live("stream-1").getId();
        insertRunning(user, "stream-1");

        assertThatThrownBy(() -> insertRunning(user, "stream-2")).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void closedPeriodsAreNotLimited() {
        UserAccount user = live("stream-1");
        jdbc.update("INSERT INTO stream_category_segment (id, user_id, stream_id, started_at, ended_at) "
                + "VALUES (?, ?, 'stream-1', ?, ?)", UUID.randomUUID(), user.getId(), Timestamp.from(START),
                Timestamp.from(START.plusSeconds(60)));
        insertRunning(user.getId(), "stream-1");

        assertThat(periods(user)).isEqualTo(2);
        assertThat(running(user)).isEqualTo(1);
    }
}
