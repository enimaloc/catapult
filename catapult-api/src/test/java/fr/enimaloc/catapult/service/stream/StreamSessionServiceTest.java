package fr.enimaloc.catapult.service.stream;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.stream.StreamSession;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("h2-test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:catapult_streamsession_it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE"
})
class StreamSessionServiceTest {

    @Autowired StreamSessionService service;
    @Autowired UserAccountRepository users;

    private UserAccount newUser() {
        UserAccount user = new UserAccount();
        user.setTwitchUsername("streamer-" + UUID.randomUUID().toString().substring(0, 8));
        return users.save(user);
    }

    @Test
    void recordStart_thenFind_returnsOngoingSession() {
        UserAccount user = newUser();
        Instant start = Instant.parse("2026-11-03T10:00:00Z");

        service.recordStart(user, "stream-1", start);

        StreamSession session = service.find(user).orElseThrow();
        assertThat(session.getStreamId()).isEqualTo("stream-1");
        assertThat(session.getStartedAt()).isEqualTo(start);
        assertThat(session.getEndedAt()).isNull();
        assertThat(session.isOngoing()).isTrue();
    }

    @Test
    void recordStart_sameUserNewStream_replacesStreamIdAndClearsEnd() {
        UserAccount user = newUser();
        service.recordStart(user, "stream-1", Instant.parse("2026-11-03T10:00:00Z"));
        service.recordEnd(user, Instant.parse("2026-11-03T11:00:00Z"));

        Instant second = Instant.parse("2026-11-03T12:00:00Z");
        service.recordStart(user, "stream-2", second);

        StreamSession session = service.find(user).orElseThrow();
        assertThat(session.getStreamId()).isEqualTo("stream-2");
        assertThat(session.getStartedAt()).isEqualTo(second);
        assertThat(session.isOngoing()).isTrue();
    }

    @Test
    void recordEnd_marksEnded() {
        UserAccount user = newUser();
        service.recordStart(user, "stream-1", Instant.parse("2026-11-03T10:00:00Z"));
        Instant end = Instant.parse("2026-11-03T11:00:00Z");

        service.recordEnd(user, end);

        StreamSession session = service.find(user).orElseThrow();
        assertThat(session.getEndedAt()).isEqualTo(end);
        assertThat(session.isOngoing()).isFalse();
    }

    @Test
    void recordEnd_withoutSession_isNoop() {
        UserAccount user = newUser();

        service.recordEnd(user, Instant.now());

        assertThat(service.find(user)).isEmpty();
    }
}
