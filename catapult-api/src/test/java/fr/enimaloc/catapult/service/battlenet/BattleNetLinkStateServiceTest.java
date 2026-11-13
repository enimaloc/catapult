package fr.enimaloc.catapult.service.battlenet;

import fr.enimaloc.catapult.common.dto.connect.BattleNetLinkStateResponse;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.battlenet.BattleNetFriendLink;
import fr.enimaloc.catapult.domain.battlenet.BattleNetServiceAccount;
import fr.enimaloc.catapult.domain.battlenet.BattleNetServiceAccount.AuthState;
import fr.enimaloc.catapult.repository.battlenet.BattleNetFriendLinkRepository;
import fr.enimaloc.catapult.repository.battlenet.BattleNetServiceAccountRepository;
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
class BattleNetLinkStateServiceTest {

    @Mock private BattleNetGateService gate;
    @Mock private BattleNetFriendLinkRepository linkRepository;
    @Mock private BattleNetServiceAccountRepository accountRepository;
    @InjectMocks private BattleNetLinkStateService service;

    private final UserAccount user = new UserAccount();

    private void open(long ready, long readyWithRoom) {
        when(gate.isAvailableFor(user)).thenReturn(true);
        when(accountRepository.countByEnabledTrueAndAuthState(AuthState.READY)).thenReturn(ready);
        when(accountRepository.countByEnabledTrueAndAuthStateAndFriendLimitReachedFalse(AuthState.READY))
                .thenReturn(readyWithRoom);
        when(linkRepository.findWithServiceAccountByUser(user)).thenReturn(Optional.empty());
    }

    @Test
    void noLinkAndFreeBotIsNone() {
        open(2, 1);

        assertThat(service.state(user)).isEqualTo(new BattleNetLinkStateResponse("NONE", null, null));
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
        BattleNetServiceAccount bot = new BattleNetServiceAccount();
        bot.setBattleTag("Bot#1234");
        BattleNetFriendLink link = new BattleNetFriendLink();
        link.setServiceAccount(bot);
        link.setBattleTag("Streamer#4321");
        when(linkRepository.findWithServiceAccountByUser(user)).thenReturn(Optional.of(link));

        assertThat(service.state(user))
                .isEqualTo(new BattleNetLinkStateResponse("PENDING", "Streamer#4321", "Bot#1234"));
    }

    @Test
    void gateClosedIsUnavailable() {
        when(gate.isAvailableFor(user)).thenReturn(false);

        assertThat(service.state(user).status()).isEqualTo("UNAVAILABLE");
    }
}
