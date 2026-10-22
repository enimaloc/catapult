package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GameStateServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-22T20:00:00Z");

    private final GameStateService service = new GameStateService(mock(ChannelEventPublisher.class));
    private final DetectedGame doom = new DetectedGame("379720", GameBinding.SourceType.STEAM, "DOOM");
    private UserAccount user;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        at(T0);
    }

    private void at(Instant now) {
        service.clock = Clock.fixed(now, ZoneOffset.UTC);
    }

    @Test
    void recentlyDetectedGame_isTheGameStillBeingPlayed() {
        service.updateState(user, doom);
        at(T0.plus(Duration.ofHours(5)));

        assertThat(service.recentlyDetectedGame(user, Duration.ofMinutes(1))).contains(doom);
    }

    @Test
    void recentlyDetectedGame_survivesTheGameNoLongerBeingDetected() {
        service.updateState(user, doom);
        at(T0.plus(Duration.ofMinutes(30)));
        service.clearState(user);
        at(T0.plus(Duration.ofMinutes(44)));

        assertThat(service.getLastKnownGame(user)).isEmpty();
        assertThat(service.recentlyDetectedGame(user, Duration.ofMinutes(15))).contains(doom);
    }

    @Test
    void recentlyDetectedGame_isEmptyOnceTheDelayHasPassed() {
        service.updateState(user, doom);
        service.clearState(user);
        at(T0.plus(Duration.ofMinutes(16)));

        assertThat(service.recentlyDetectedGame(user, Duration.ofMinutes(15))).isEmpty();
    }

    @Test
    void recentlyDetectedGame_isEmptyWhenNothingWasEverDetected() {
        assertThat(service.recentlyDetectedGame(user, Duration.ofMinutes(15))).isEmpty();
    }
}
