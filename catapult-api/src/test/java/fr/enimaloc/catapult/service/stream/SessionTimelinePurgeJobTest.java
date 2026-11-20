package fr.enimaloc.catapult.service.stream;

import fr.enimaloc.catapult.domain.stream.StreamCategorySegment;
import fr.enimaloc.catapult.domain.stream.StreamSession;
import fr.enimaloc.catapult.repository.stream.StreamCategorySegmentRepository;
import fr.enimaloc.catapult.site.SiteRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE",
        "logging.level.org.hibernate.tool.schema=ERROR"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SessionTimelinePurgeJobTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    @Autowired StreamCategorySegmentRepository repository;
    @Autowired TestEntityManager em;

    private final SiteRole siteRole = mock(SiteRole.class);

    private SessionTimelinePurgeJob job() {
        return new SessionTimelinePurgeJob(repository, siteRole, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void session(UUID user, String streamId, Instant endedAt) {
        StreamSession s = new StreamSession();
        s.setUserId(user);
        s.setStreamId(streamId);
        s.setStartedAt(NOW.minus(Duration.ofDays(3)));
        s.setEndedAt(endedAt);
        em.persist(s);
    }

    private UUID segment(UUID user, String streamId, Instant endedAt) {
        StreamCategorySegment s = new StreamCategorySegment();
        s.setUserId(user);
        s.setStreamId(streamId);
        s.setStartedAt(NOW.minus(Duration.ofDays(3)));
        s.setEndedAt(endedAt);
        return em.persist(s).getId();
    }

    private boolean exists(UUID id) {
        em.flush();
        em.clear();
        return repository.existsById(id);
    }

    @Test
    void deletesSegmentsOfSessionsEndedMoreThanADayAgo() {
        when(siteRole.isActive()).thenReturn(true);
        UUID old = UUID.randomUUID();
        UUID recent = UUID.randomUUID();
        session(old, "s-old", NOW.minus(Duration.ofHours(25)));
        session(recent, "s-recent", NOW.minus(Duration.ofHours(23)));
        UUID oldSegment = segment(old, "s-old", NOW.minus(Duration.ofHours(25)));
        UUID recentSegment = segment(recent, "s-recent", NOW.minus(Duration.ofHours(23)));
        // an earlier live of a member who has since streamed again
        UUID previousLive = segment(recent, "s-before", NOW.minus(Duration.ofHours(30)));
        UUID previousLiveRecent = segment(recent, "s-before-2", NOW.minus(Duration.ofHours(2)));
        em.flush();

        job().run();

        assertThat(exists(oldSegment)).isFalse();
        assertThat(exists(previousLive)).isFalse();
        assertThat(exists(recentSegment)).isTrue();
        assertThat(exists(previousLiveRecent)).isTrue();
    }

    @Test
    void keepsTheCurrentSession() {
        when(siteRole.isActive()).thenReturn(true);
        UUID user = UUID.randomUUID();
        session(user, "s-live", null);
        UUID running = segment(user, "s-live", null);
        UUID closed = segment(user, "s-live", NOW.minus(Duration.ofHours(30)));
        UUID staleRunning = segment(user, "s-gone", null);
        em.flush();

        job().run();

        assertThat(exists(running)).isTrue();
        assertThat(exists(closed)).isTrue();
        assertThat(exists(staleRunning)).isTrue();
    }

    @Test
    void passiveSiteDoesNothing() {
        when(siteRole.isActive()).thenReturn(false);
        UUID user = UUID.randomUUID();
        session(user, "s-old", NOW.minus(Duration.ofHours(25)));
        UUID id = segment(user, "s-old", NOW.minus(Duration.ofHours(25)));
        em.flush();

        job().run();

        assertThat(exists(id)).isTrue();
    }
}
