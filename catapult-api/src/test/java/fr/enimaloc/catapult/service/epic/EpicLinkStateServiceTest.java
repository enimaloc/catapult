package fr.enimaloc.catapult.service.epic;

import fr.enimaloc.catapult.common.dto.connect.EpicLinkStateResponse;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.epic.EpicFriendLink;
import fr.enimaloc.catapult.domain.epic.EpicFriendLink.Status;
import fr.enimaloc.catapult.domain.epic.EpicServiceAccount;
import fr.enimaloc.catapult.domain.epic.EpicServiceAccount.AuthState;
import fr.enimaloc.catapult.repository.epic.EpicFriendLinkRepository;
import fr.enimaloc.catapult.repository.epic.EpicServiceAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EpicLinkStateServiceTest {

    @Mock private EpicGateService gate;
    @Mock private EpicFriendLinkRepository linkRepository;
    @Mock private EpicServiceAccountRepository accountRepository;

    private EpicLinkStateService service;
    private final UserAccount user = new UserAccount();

    @BeforeEach
    void setUp() {
        service = new EpicLinkStateService(gate, linkRepository, accountRepository);
        when(gate.isAvailableFor(user)).thenReturn(true);
        when(accountRepository.countByEnabledTrueAndAuthState(AuthState.READY)).thenReturn(1L);
        when(accountRepository.countByEnabledTrueAndAuthStateAndFriendLimitReachedFalse(AuthState.READY))
                .thenReturn(1L);
        when(linkRepository.findWithServiceAccountByUser(any())).thenReturn(Optional.empty());
    }

    private EpicFriendLink link(Status status) {
        EpicServiceAccount bot = new EpicServiceAccount();
        bot.setDisplayName("CatapultBot");
        EpicFriendLink link = new EpicFriendLink();
        link.setServiceAccount(bot);
        link.setDisplayName("Streamer");
        link.setStatus(status);
        return link;
    }

    @Test
    void notInTheExperiment_isUnavailable() {
        when(gate.isAvailableFor(user)).thenReturn(false);
        assertThat(service.state(user)).isEqualTo(new EpicLinkStateResponse("UNAVAILABLE", null, null));
    }

    @Test
    void noReadyBot_isUnavailable_evenWhenLinked() {
        when(accountRepository.countByEnabledTrueAndAuthState(AuthState.READY)).thenReturn(0L);
        when(linkRepository.findWithServiceAccountByUser(user)).thenReturn(Optional.of(link(Status.ACCEPTED)));
        assertThat(service.state(user).status()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void noLink_isNone_orFullWhenEveryBotIsFull() {
        assertThat(service.state(user).status()).isEqualTo("NONE");
        when(accountRepository.countByEnabledTrueAndAuthStateAndFriendLimitReachedFalse(AuthState.READY))
                .thenReturn(0L);
        assertThat(service.state(user).status()).isEqualTo("FULL");
    }

    @Test
    void aLinkedUserKeepsTheLinkWhenEveryBotIsFull() {
        when(accountRepository.countByEnabledTrueAndAuthStateAndFriendLimitReachedFalse(AuthState.READY))
                .thenReturn(0L);
        when(linkRepository.findWithServiceAccountByUser(user)).thenReturn(Optional.of(link(Status.ACCEPTED)));
        assertThat(service.state(user)).isEqualTo(new EpicLinkStateResponse("ACCEPTED", "Streamer", null));
    }

    @Test
    void theBotNameShowsWhileTheStreamerHasToAct() {
        when(linkRepository.findWithServiceAccountByUser(user)).thenReturn(Optional.of(link(Status.PENDING)));
        assertThat(service.state(user)).isEqualTo(new EpicLinkStateResponse("PENDING", "Streamer", "CatapultBot"));
        when(linkRepository.findWithServiceAccountByUser(user)).thenReturn(Optional.of(link(Status.INVITE_REJECTED)));
        assertThat(service.state(user).serviceAccountName()).isEqualTo("CatapultBot");
    }
}
