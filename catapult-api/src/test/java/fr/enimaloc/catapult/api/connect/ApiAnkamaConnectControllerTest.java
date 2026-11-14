package fr.enimaloc.catapult.api.connect;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.ankama.AnkamaFriendLink;
import fr.enimaloc.catapult.domain.ankama.AnkamaServiceAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.ankama.AnkamaFriendLinkRepository;
import fr.enimaloc.catapult.repository.ankama.AnkamaServiceAccountRepository;
import fr.enimaloc.catapult.service.ankama.AnkamaFriendService;
import fr.enimaloc.catapult.service.ankama.AnkamaFriendService.AnkamaEnrollmentException;
import fr.enimaloc.catapult.service.ankama.AnkamaFriendService.AnkamaEnrollmentException.Reason;
import fr.enimaloc.catapult.service.ankama.AnkamaGateService;
import fr.enimaloc.catapult.service.ankama.AnkamaLinkStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
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
class ApiAnkamaConnectControllerTest {

    @Mock private AnkamaFriendService friendService;
    @Mock private AnkamaGateService gateService;
    @Mock private UserAccountRepository userAccountRepository;
    @Mock private AnkamaFriendLinkRepository linkRepository;
    @Mock private AnkamaServiceAccountRepository accountRepository;
    @Mock private Jwt jwt;

    private ApiAnkamaConnectController controller;
    private UserAccount user;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        controller = new ApiAnkamaConnectController(friendService,
                new AnkamaLinkStateService(gateService, linkRepository, accountRepository), gateService,
                new ApiUserResolver(userAccountRepository));
        user = new UserAccount();
        when(jwt.getSubject()).thenReturn(userId.toString());
        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));
        when(gateService.isAvailableFor(user)).thenReturn(true);
        when(accountRepository.countByEnabledTrueAndAuthState(AnkamaServiceAccount.AuthState.READY)).thenReturn(1L);
        when(accountRepository.countByEnabledTrueAndAuthStateAndFriendLimitReachedFalse(
                AnkamaServiceAccount.AuthState.READY)).thenReturn(1L);
    }

    private void pendingLink() {
        var bot = new AnkamaServiceAccount();
        bot.setNickname("Bot");
        bot.setTag("0001");
        var link = new AnkamaFriendLink();
        link.setUser(user);
        link.setServiceAccount(bot);
        link.setRequestedName("enimaloc");
        link.setRequestedTag("6094");
        link.setStatus(AnkamaFriendLink.Status.PENDING);
        when(linkRepository.findWithServiceAccountByUser(user)).thenReturn(Optional.of(link));
    }

    @Test
    void get_noLink_isNone_andAClosedGateIsUnavailable() {
        when(linkRepository.findWithServiceAccountByUser(user)).thenReturn(Optional.empty());
        assertThat(controller.status(userId, jwt).status()).isEqualTo("NONE");

        when(gateService.isAvailableFor(user)).thenReturn(false);
        assertThat(controller.status(userId, jwt).status()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void connect_put_goodNameTag_isPending() {
        pendingLink();

        var response = controller.enroll(userId, jwt, Map.of("nameTag", "enimaloc#6094"));

        verify(friendService).enroll(user, "enimaloc#6094");
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.nameTag()).isEqualTo("enimaloc#6094");
        assertThat(response.serviceAccountTag()).isEqualTo("Bot#0001");
    }

    @Test
    void connect_put_badNameTag_is400() {
        when(friendService.enroll(any(), any())).thenThrow(new IllegalArgumentException("not a name#tag"));

        assertThatThrownBy(() -> controller.enroll(userId, jwt, Map.of("nameTag", "nope")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void connect_put_noCapacity_andAlreadyLinked_are409WithTheirReason() {
        when(friendService.enroll(any(), any()))
                .thenThrow(new AnkamaEnrollmentException(Reason.NO_CAPACITY, "full"))
                .thenThrow(new AnkamaEnrollmentException(Reason.ALREADY_LINKED, "taken"));

        assertThatThrownBy(() -> controller.enroll(userId, jwt, Map.of("nameTag", "a#1")))
                .isInstanceOfSatisfying(ApiProblem.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(409);
                    assertThat(e.getBody().getProperties()).containsEntry("reason", "FULL");
                });
        assertThatThrownBy(() -> controller.enroll(userId, jwt, Map.of("nameTag", "a#1")))
                .isInstanceOfSatisfying(ApiProblem.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(409);
                    assertThat(e.getBody().getProperties()).containsEntry("reason", "ALREADY_LINKED");
                });
    }

    @Test
    void connect_put_noAccountAvailable_is503() {
        when(friendService.enroll(any(), any()))
                .thenThrow(new AnkamaEnrollmentException(Reason.NO_ACCOUNT_AVAILABLE, "none"));

        assertThatThrownBy(() -> controller.enroll(userId, jwt, Map.of("nameTag", "a#1")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void connect_closedGate_refusesEveryWrite_before_anyValidation() {
        when(gateService.isAvailableFor(user)).thenReturn(false);

        assertThatThrownBy(() -> controller.enroll(userId, jwt, Map.of("nameTag", "nope")))
                .isInstanceOf(ApiProblem.class);
        assertThatThrownBy(() -> controller.resend(userId, jwt)).isInstanceOf(ApiProblem.class);
        assertThatThrownBy(() -> controller.unenroll(userId, jwt)).isInstanceOf(ApiProblem.class);
        verify(friendService, never()).enroll(any(), any());
        verify(friendService, never()).resendInvite(any());
        verify(friendService, never()).unenroll(any());
    }

    @Test
    void resend_and_delete_callTheFriendService() {
        pendingLink();

        assertThat(controller.resend(userId, jwt).status()).isEqualTo("PENDING");
        controller.unenroll(userId, jwt);

        verify(friendService).resendInvite(user);
        verify(friendService).unenroll(user);
    }
}
