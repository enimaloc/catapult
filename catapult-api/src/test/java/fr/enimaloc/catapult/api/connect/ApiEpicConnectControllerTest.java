package fr.enimaloc.catapult.api.connect;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.epic.EpicFriendLink;
import fr.enimaloc.catapult.domain.epic.EpicServiceAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.epic.EpicFriendLinkRepository;
import fr.enimaloc.catapult.repository.epic.EpicServiceAccountRepository;
import fr.enimaloc.catapult.service.epic.EpicFriendService;
import fr.enimaloc.catapult.service.epic.EpicGateService;
import fr.enimaloc.catapult.service.epic.EpicLinkStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiEpicConnectControllerTest {

    @Mock private EpicFriendService friendService;
    @Mock private EpicGateService gateService;
    @Mock private UserAccountRepository userAccountRepository;
    @Mock private EpicFriendLinkRepository linkRepository;
    @Mock private EpicServiceAccountRepository accountRepository;
    @Mock private Jwt jwt;

    private ApiEpicConnectController controller;
    private UserAccount user;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        controller = new ApiEpicConnectController(friendService,
                new EpicLinkStateService(gateService, linkRepository, accountRepository), gateService,
                new ApiUserResolver(userAccountRepository));
        user = new UserAccount();
        when(jwt.getSubject()).thenReturn(userId.toString());
        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));
        when(gateService.isAvailableFor(user)).thenReturn(true);
        when(accountRepository.countByEnabledTrueAndAuthState(EpicServiceAccount.AuthState.READY)).thenReturn(1L);
        when(accountRepository.countByEnabledTrueAndAuthStateAndFriendLimitReachedFalse(
                EpicServiceAccount.AuthState.READY)).thenReturn(1L);
    }

    private void pendingLink() {
        var bot = new EpicServiceAccount();
        bot.setDisplayName("Catapult-1");
        var link = new EpicFriendLink();
        link.setUser(user);
        link.setServiceAccount(bot);
        link.setEpicAccountId("ffffffffffffffffffffffffffffffff");
        link.setDisplayName("enimaloc");
        link.setStatus(EpicFriendLink.Status.PENDING);
        when(linkRepository.findWithServiceAccountByUser(user)).thenReturn(Optional.of(link));
    }

    @Test
    void connect_routes_followTheAnkamaAccessRules() {
        // every route is the member's own, as for Ankama; linking goes through OAuth, so no PUT
        for (Method method : ApiEpicConnectController.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())) continue;
            assertThat(method.getAnnotation(PreAuthorize.class)).as(method.getName()).isNotNull()
                    .extracting(PreAuthorize::value).isEqualTo("@authz.isSelf(#userId)");
        }
        assertThat(Arrays.stream(ApiEpicConnectController.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(PutMapping.class))).isEmpty();

        // reading stays open behind a closed gate, every write is refused before reaching the friend service
        when(linkRepository.findWithServiceAccountByUser(user)).thenReturn(Optional.empty());
        assertThat(controller.status(userId, jwt).status()).isEqualTo("NONE");
        when(gateService.isAvailableFor(user)).thenReturn(false);
        assertThat(controller.status(userId, jwt).status()).isEqualTo("UNAVAILABLE");
        assertThatThrownBy(() -> controller.resend(userId, jwt)).isInstanceOf(ApiProblem.class);
        assertThatThrownBy(() -> controller.unenroll(userId, jwt)).isInstanceOf(ApiProblem.class);
        verify(friendService, never()).resendInvite(any());
        verify(friendService, never()).unenroll(any());
    }

    @Test
    void resend_and_delete_callTheFriendService() {
        pendingLink();

        var state = controller.resend(userId, jwt);
        controller.unenroll(userId, jwt);

        assertThat(state.status()).isEqualTo("PENDING");
        assertThat(state.displayName()).isEqualTo("enimaloc");
        assertThat(state.serviceAccountName()).isEqualTo("Catapult-1");
        verify(friendService).resendInvite(user);
        verify(friendService).unenroll(user);
    }
}
