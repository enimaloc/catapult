package fr.enimaloc.catapult.service.stream;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.stream.StreamCategorySegment;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest
@ActiveProfiles("h2-test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:catapult_timeline_it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE"
})
class SessionTimelineServiceTest {

    private static final Instant START = Instant.parse("2026-11-20T18:00:00Z");

    @Autowired SessionTimelineService timeline;
    @Autowired StreamSessionService sessions;
    @Autowired UserAccountRepository users;

    private UserAccount newUser() {
        UserAccount user = new UserAccount();
        user.setTwitchUsername("streamer-" + UUID.randomUUID().toString().substring(0, 8));
        return users.save(user);
    }

    private UserAccount liveUser() {
        UserAccount user = newUser();
        sessions.recordStart(user, "stream-1", START);
        return user;
    }

    private static Instant at(long minutes) {
        return START.plus(Duration.ofMinutes(minutes));
    }

    @Test
    void observeOpensTheFirstSegment() {
        UserAccount user = liveUser();

        timeline.observe(user, "1", "Factorio", "Factorio", START);

        List<StreamCategorySegment> segments = timeline.currentSession(user);
        assertThat(segments).singleElement().satisfies(segment -> {
            assertThat(segment.getUserId()).isEqualTo(user.getId());
            assertThat(segment.getStreamId()).isEqualTo("stream-1");
            assertThat(segment.getCategoryId()).isEqualTo("1");
            assertThat(segment.getCategoryName()).isEqualTo("Factorio");
            assertThat(segment.getGameName()).isEqualTo("Factorio");
            assertThat(segment.getStartedAt()).isEqualTo(START);
            assertThat(segment.getEndedAt()).isNull();
        });
    }

    @Test
    void samePairDoesNothing() {
        UserAccount user = liveUser();
        timeline.observe(user, "1", "Factorio", "Factorio", START);

        timeline.observe(user, "1", "Factorio", "Factorio", at(10));
        timeline.observeCategory(user, "1", "Factorio", at(20));
        timeline.observeGame(user, "Factorio", at(30));

        assertThat(timeline.currentSession(user)).singleElement()
                .satisfies(segment -> assertThat(segment.getEndedAt()).isNull());
    }

    @Test
    void newCategoryClosesAndOpens() {
        UserAccount user = liveUser();
        timeline.observe(user, "1", "Factorio", null, START);

        timeline.observeCategory(user, "2", "Just Chatting", at(60));

        assertThat(timeline.currentSession(user))
                .extracting(StreamCategorySegment::getCategoryName, StreamCategorySegment::getGameName,
                        StreamCategorySegment::getStartedAt, StreamCategorySegment::getEndedAt)
                .containsExactly(
                        tuple("Factorio", null, START, at(60)),
                        tuple("Just Chatting", null, at(60), null));
    }

    @Test
    void newGameInTheSameCategoryClosesAndOpens() {
        UserAccount user = liveUser();
        timeline.observe(user, "3", "Games+Demos", null, START);

        timeline.observeGame(user, "MOLTYN", at(15));
        timeline.observeGame(user, null, at(40));

        assertThat(timeline.currentSession(user))
                .extracting(StreamCategorySegment::getCategoryId, StreamCategorySegment::getGameName,
                        StreamCategorySegment::getStartedAt, StreamCategorySegment::getEndedAt)
                .containsExactly(
                        tuple("3", null, START, at(15)),
                        tuple("3", "MOLTYN", at(15), at(40)),
                        tuple("3", null, at(40), null));
    }

    @Test
    void unknownCategoryIsFilledWithoutSplitting() {
        UserAccount user = liveUser();
        timeline.observe(user, null, null, "Factorio", START);

        timeline.observeCategory(user, "1", "Factorio", at(5));

        assertThat(timeline.currentSession(user)).singleElement().satisfies(segment -> {
            assertThat(segment.getCategoryId()).isEqualTo("1");
            assertThat(segment.getCategoryName()).isEqualTo("Factorio");
            assertThat(segment.getGameName()).isEqualTo("Factorio");
            assertThat(segment.getStartedAt()).isEqualTo(START);
            assertThat(segment.getEndedAt()).isNull();
        });
    }

    @Test
    void noSessionNoSegment() {
        UserAccount user = newUser();
        timeline.observe(user, "1", "Factorio", "Factorio", START);
        assertThat(timeline.currentSession(user)).isEmpty();

        sessions.recordStart(user, "stream-1", START);
        sessions.recordEnd(user, at(30));
        timeline.observe(user, "1", "Factorio", "Factorio", at(40));

        assertThat(timeline.currentSession(user)).isEmpty();
    }

    @Test
    void closeOpenEndsTheSegment() {
        UserAccount user = liveUser();
        timeline.observe(user, "1", "Factorio", "Factorio", START);

        timeline.closeOpen(user, at(90));

        assertThat(timeline.currentSession(user)).singleElement()
                .satisfies(segment -> assertThat(segment.getEndedAt()).isEqualTo(at(90)));
    }

    @Test
    void closeOpenEndsThePeriodOfASessionThatJustEnded() {
        UserAccount user = liveUser();
        timeline.observe(user, "1", "Factorio", "Factorio", START);
        // StreamOfflineEvent is published once the end is recorded: the session is no longer ongoing
        sessions.recordEnd(user, at(90));

        timeline.closeOpen(user, at(90));

        assertThat(timeline.currentSession(user)).singleElement()
                .satisfies(segment -> assertThat(segment.getEndedAt()).isEqualTo(at(90)));
    }

    @Test
    void currentSessionIgnoresAPreviousStream() {
        UserAccount user = liveUser();
        timeline.observe(user, "1", "Factorio", "Factorio", START);
        // The previous stream ended while nothing was listening: its segment is still open
        sessions.recordStart(user, "stream-2", at(600));

        timeline.observe(user, "1", "Factorio", "Factorio", at(600));

        assertThat(timeline.currentSession(user)).singleElement().satisfies(segment -> {
            assertThat(segment.getStreamId()).isEqualTo("stream-2");
            assertThat(segment.getStartedAt()).isEqualTo(at(600));
            assertThat(segment.getEndedAt()).isNull();
        });
    }

    @Test
    void addPastMovesTheSessionStartBack() {
        UserAccount user = liveUser();
        timeline.observe(user, "3", "Games+Demos", "MOLTYN", START);

        timeline.addPast(user, "2", "Just Chatting", null, Duration.ofHours(1));
        timeline.addPast(user, "1", "Factorio", "Factorio", Duration.ofMinutes(150));

        assertThat(timeline.currentSession(user))
                .extracting(StreamCategorySegment::getCategoryName, StreamCategorySegment::getGameName,
                        StreamCategorySegment::getStartedAt, StreamCategorySegment::getEndedAt)
                .containsExactly(
                        tuple("Factorio", "Factorio", at(-210), at(-60)),
                        tuple("Just Chatting", null, at(-60), START),
                        tuple("Games+Demos", "MOLTYN", START, null));
        assertThat(sessions.find(user).orElseThrow().getStartedAt()).isEqualTo(at(-210));
    }

    @Test
    void addPastRefusesANonPositiveDuration() {
        UserAccount user = liveUser();

        assertThatThrownBy(() -> timeline.addPast(user, "1", "Factorio", null, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(timeline.currentSession(user)).isEmpty();
    }
}
