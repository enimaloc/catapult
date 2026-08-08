package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.notification.TwitchatNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class StreamStateServiceTest {

    private StreamStateService service;
    private TwitchatNotifier twitchatNotifier;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        twitchatNotifier = org.mockito.Mockito.mock(TwitchatNotifier.class);
        service = new StreamStateService(
                org.mockito.Mockito.mock(fr.enimaloc.catapult.service.notification.ChannelEventPublisher.class),
                twitchatNotifier);
        user = new UserAccount();
        user.setId(UUID.randomUUID());
    }

    @Test
    void isLive_defaultsFalse() {
        assertThat(service.isLive(user)).isFalse();
    }

    @Test
    void setLive_updatesState() {
        service.setLive(user, true);
        assertThat(service.isLive(user)).isTrue();
        service.setLive(user, false);
        assertThat(service.isLive(user)).isFalse();
    }

    @Test
    void storePending_getPending_roundtrip() {
        GameBinding binding = new GameBinding();
        service.storePending(user, binding);
        assertThat(service.getPending(user)).contains(binding);
    }

    @Test
    void getPending_emptyByDefault() {
        assertThat(service.getPending(user)).isEmpty();
    }

    @Test
    void clearPending_removesBinding() {
        service.storePending(user, new GameBinding());
        service.clearPending(user);
        assertThat(service.getPending(user)).isEmpty();
    }

    @Test
    void clear_removesLiveAndPending() {
        service.setLive(user, true);
        service.storePending(user, new GameBinding());
        service.clear(user);
        assertThat(service.isLive(user)).isFalse();
        assertThat(service.getPending(user)).isEmpty();
    }

    @Test
    void countLive_zeroWhenEmpty() {
        assertThat(service.countLive()).isEqualTo(0L);
    }

    @Test
    void countLive_countsOnlyTrueEntries() {
        UserAccount u1 = new UserAccount(); u1.setId(UUID.randomUUID());
        UserAccount u2 = new UserAccount(); u2.setId(UUID.randomUUID());
        UserAccount u3 = new UserAccount(); u3.setId(UUID.randomUUID());
        service.setLive(u1, true);
        service.setLive(u2, false);
        service.setLive(u3, true);
        assertThat(service.countLive()).isEqualTo(2L);
    }

    @Test
    void setLive_transitionToLive_botEnabled_notifiesTwitchat() {
        UserAccount botEnabledUser = new UserAccount();
        botEnabledUser.setId(UUID.randomUUID());
        botEnabledUser.setBotEnabled(true);

        service.setLive(botEnabledUser, true);

        verify(twitchatNotifier).onStreamStarted(botEnabledUser);
    }

    @Test
    void setLive_transitionToLive_botDisabled_doesNotNotifyTwitchat() {
        UserAccount botDisabledUser = new UserAccount();
        botDisabledUser.setId(UUID.randomUUID());
        botDisabledUser.setBotEnabled(false);

        service.setLive(botDisabledUser, true);

        verifyNoInteractions(twitchatNotifier);
    }
}
