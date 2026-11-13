package fr.enimaloc.catapult.api.connect;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.battlenet.BattleNetFriendLink;
import fr.enimaloc.catapult.domain.battlenet.BattleNetServiceAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.battlenet.BattleNetFriendLinkRepository;
import fr.enimaloc.catapult.repository.battlenet.BattleNetServiceAccountRepository;
import fr.enimaloc.catapult.service.battlenet.BattleNetFriendService;
import fr.enimaloc.catapult.service.battlenet.BattleNetGateService;
import fr.enimaloc.catapult.service.battlenet.BattleNetLinkStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiBattleNetConnectControllerTest {

    @Mock private BattleNetFriendService friendService;
    @Mock private BattleNetGateService gateService;
    @Mock private UserAccountRepository userAccountRepository;
    @Mock private BattleNetFriendLinkRepository linkRepository;
    @Mock private BattleNetServiceAccountRepository accountRepository;
    @Mock private Jwt jwt;

    private ApiBattleNetConnectController controller;
    private UserAccount user;

    @BeforeEach
    void setup() {
        controller = new ApiBattleNetConnectController(friendService,
                new BattleNetLinkStateService(gateService, linkRepository, accountRepository),
                gateService, userAccountRepository);
        user = new UserAccount();
        UUID userId = UUID.randomUUID();
        when(jwt.getSubject()).thenReturn(userId.toString());
        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));
        when(gateService.isAvailableFor(user)).thenReturn(true);
        when(accountRepository.countByEnabledTrueAndAuthState(BattleNetServiceAccount.AuthState.READY))
                .thenReturn(1L);
        when(accountRepository.countByEnabledTrueAndAuthStateAndFriendLimitReachedFalse(
                BattleNetServiceAccount.AuthState.READY)).thenReturn(1L);
    }

    @Test
    void get_noLink_returnsStatusNone() {
        when(linkRepository.findWithServiceAccountByUser(user)).thenReturn(Optional.empty());

        assertThat(controller.status(UUID.randomUUID(), jwt).status()).isEqualTo("NONE");
    }

    @Test
    void get_pendingLink_exposesBothTags() {
        var bot = new BattleNetServiceAccount();
        bot.setBattleTag("Bot#1111");
        var link = new BattleNetFriendLink();
        link.setUser(user);
        link.setServiceAccount(bot);
        link.setBattleTag("Name#1234");
        link.setStatus(BattleNetFriendLink.Status.PENDING);
        when(linkRepository.findWithServiceAccountByUser(user)).thenReturn(Optional.of(link));

        var response = controller.status(UUID.randomUUID(), jwt);

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.battleTag()).isEqualTo("Name#1234");
        assertThat(response.serviceAccountBattleTag()).isEqualTo("Bot#1111");
    }

    @Test
    void get_closedGate_returnsUnavailable() {
        when(gateService.isAvailableFor(user)).thenReturn(false);

        assertThat(controller.status(UUID.randomUUID(), jwt).status()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void delete_unenrolls() {
        controller.unenroll(UUID.randomUUID(), jwt);

        verify(friendService).unenroll(user);
    }

    @Test
    void delete_closedGate_isRefused() {
        when(gateService.isAvailableFor(user)).thenReturn(false);

        assertThatThrownBy(() -> controller.unenroll(UUID.randomUUID(), jwt)).isInstanceOf(ApiProblem.class);
        verify(friendService, never()).unenroll(user);
    }
}
