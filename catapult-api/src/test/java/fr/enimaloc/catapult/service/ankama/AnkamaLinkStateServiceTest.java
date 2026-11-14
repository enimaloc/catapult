package fr.enimaloc.catapult.service.ankama;

import fr.enimaloc.catapult.common.dto.connect.AnkamaLinkStateResponse;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.ankama.AnkamaFriendLink;
import fr.enimaloc.catapult.domain.ankama.AnkamaServiceAccount;
import fr.enimaloc.catapult.domain.ankama.AnkamaServiceAccount.AuthState;
import fr.enimaloc.catapult.repository.ankama.AnkamaFriendLinkRepository;
import fr.enimaloc.catapult.repository.ankama.AnkamaServiceAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnkamaLinkStateServiceTest {

    @Mock private AnkamaGateService gate;
    @Mock private AnkamaFriendLinkRepository linkRepository;
    @Mock private AnkamaServiceAccountRepository accountRepository;
    @InjectMocks private AnkamaLinkStateService service;

    private final UserAccount user = new UserAccount();

    private void open(long ready, long readyWithRoom) {
        when(gate.isAvailableFor(user)).thenReturn(true);
        when(accountRepository.countByEnabledTrueAndAuthState(AuthState.READY)).thenReturn(ready);
        when(accountRepository.countByEnabledTrueAndAuthStateAndFriendLimitReachedFalse(AuthState.READY))
                .thenReturn(readyWithRoom);
        when(linkRepository.findWithServiceAccountByUser(user)).thenReturn(Optional.empty());
    }

    private AnkamaFriendLink link(AnkamaFriendLink.Status status) {
        AnkamaServiceAccount bot = new AnkamaServiceAccount();
        bot.setNickname("Bot");
        bot.setTag("1234");
        AnkamaFriendLink link = new AnkamaFriendLink();
        link.setServiceAccount(bot);
        link.setRequestedName("Streamer");
        link.setRequestedTag("4321");
        link.setStatus(status);
        when(linkRepository.findWithServiceAccountByUser(user)).thenReturn(Optional.of(link));
        return link;
    }

    @Test
    void noLinkAndFreeBotIsNone() {
        open(2, 1);

        assertThat(service.state(user)).isEqualTo(new AnkamaLinkStateResponse("NONE", null, null));
    }

    @Test
    void noReadyBotIsUnavailable() {
        open(0, 0);

        assertThat(service.state(user).status()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void allBotsFullIsFull() {
        open(2, 0);

        assertThat(service.state(user).status()).isEqualTo("FULL");
    }

    @Test
    void pendingLinkExposesTheDesignatedBotTag() {
        open(1, 0);
        link(AnkamaFriendLink.Status.PENDING);

        assertThat(service.state(user)).isEqualTo(new AnkamaLinkStateResponse("PENDING", "Streamer#4321", "Bot#1234"));
    }

    @Test
    void rejectedInviteStillExposesTheBotTag() {
        open(1, 1);
        link(AnkamaFriendLink.Status.INVITE_REJECTED);

        assertThat(service.state(user).serviceAccountTag()).isEqualTo("Bot#1234");
    }

    @Test
    void acceptedLinkHidesTheBotTag() {
        open(1, 1);
        link(AnkamaFriendLink.Status.ACCEPTED);

        assertThat(service.state(user)).isEqualTo(new AnkamaLinkStateResponse("ACCEPTED", "Streamer#4321", null));
    }

    @Test
    void linkedUserKeepsTheirLinkWhenEveryBotIsFull() {
        open(1, 0);
        link(AnkamaFriendLink.Status.ACCEPTED);

        assertThat(service.state(user).status()).isEqualTo("ACCEPTED");
    }

    @Test
    void gateClosedIsUnavailable() {
        when(gate.isAvailableFor(user)).thenReturn(false);

        assertThat(service.state(user).status()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void noReadyBotIsUnavailableEvenToALinkedUser() {
        open(0, 0);
        link(AnkamaFriendLink.Status.ACCEPTED);

        assertThat(service.state(user).status()).isEqualTo("UNAVAILABLE");
    }
}
