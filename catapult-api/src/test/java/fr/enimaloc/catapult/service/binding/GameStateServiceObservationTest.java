package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Telling the first observation of a user since start (unknown state) apart from the later ones. */
class GameStateServiceObservationTest {

    private final GameStateService state = new GameStateService(mock(ChannelEventPublisher.class));

    private static UserAccount user() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        return user;
    }

    @Test
    void onlyTheFirstObservationSinceStart_isFirst() {
        UserAccount user = user();

        assertThat(state.markObserved(user)).isTrue();
        assertThat(state.markObserved(user)).isFalse();
        assertThat(state.markObserved(user())).isTrue();
    }

    @Test
    void aStateRecordedOrCleared_isAKnownState() {
        UserAccount recorded = user();
        UserAccount cleared = user();

        state.updateState(recorded, new DetectedGame("620", GameBinding.SourceType.STEAM, "Portal 2"));
        state.clearState(cleared);

        assertThat(state.markObserved(recorded)).isFalse();
        assertThat(state.markObserved(cleared)).isFalse();
    }
}
