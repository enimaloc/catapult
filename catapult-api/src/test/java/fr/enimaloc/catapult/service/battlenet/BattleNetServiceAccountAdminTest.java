package fr.enimaloc.catapult.service.battlenet;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.domain.battlenet.BattleNetFriendLink;
import fr.enimaloc.catapult.domain.battlenet.BattleNetServiceAccount;
import fr.enimaloc.catapult.domain.battlenet.BattleNetServiceAccount.AuthState;
import fr.enimaloc.catapult.repository.battlenet.BattleNetFriendLinkRepository;
import fr.enimaloc.catapult.repository.battlenet.BattleNetServiceAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.battlenet.BattleNetServiceAccountAdmin.AccountDto;
import fr.enimaloc.catapult.service.battlenet.login.BattleNetLoginCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BattleNetServiceAccountAdminTest {

    private final BattleNetServiceAccountRepository accounts = mock(BattleNetServiceAccountRepository.class);
    private final BattleNetFriendLinkRepository links = mock(BattleNetFriendLinkRepository.class);
    private final TokenEncryptionService encryption = mock(TokenEncryptionService.class);
    private final BattleNetLoginCoordinator coordinator = mock(BattleNetLoginCoordinator.class);
    private final BattleNetSessionManager sessions = mock(BattleNetSessionManager.class);
    private final BattleNetServiceAccountAdmin admin =
            new BattleNetServiceAccountAdmin(accounts, links, encryption, coordinator, sessions);
    private final BattleNetServiceAccount account = new BattleNetServiceAccount();
    private CompletableFuture<Void> login;

    @BeforeEach
    void setUp() {
        account.setId(UUID.randomUUID());
        account.setLabel("bot-1");
        account.setEmail("bot-mailbox@example.com");
        account.setPasswordEnc("enc:old");
        account.setStorageStateEnc("enc:state");
        account.setBattleTag("Bot#1234");
        account.setAuthState(AuthState.READY);
        login = new CompletableFuture<>();
                when(accounts.findAll()).thenReturn(List.of(account));
        when(accounts.findById(account.getId())).thenReturn(Optional.of(account));
        when(accounts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(encryption.encrypt(anyString())).thenAnswer(inv -> "enc:" + inv.getArgument(0));
        when(coordinator.startInteractive(any())).thenReturn(login);
        when(sessions.sessionState(any())).thenReturn("READY");
    }

    @Test
    void createEncryptsPasswordAndStartsLogin() {
        when(accounts.save(any())).thenAnswer(inv -> {
            BattleNetServiceAccount saved = inv.getArgument(0);
            saved.setId(account.getId());
            return saved;
        });

        AccountDto dto = admin.create("bot-2", "mail@example.com", "s3cret");

        verify(accounts).save(org.mockito.ArgumentMatchers.argThat(saved ->
                saved.getPasswordEnc().equals("enc:s3cret") && saved.getEmail().equals("mail@example.com")
                        && saved.getAuthState() == AuthState.NEW));
        verify(coordinator).startInteractive(account.getId());
        assertThat(dto.authState()).isEqualTo("NEW");
    }

    @Test
    void sessionIsRestartedOnceTheLoginIsReady() {
        admin.reauth(account.getId());
        verify(sessions, never()).restart(any());

        login.complete(null);

        verify(sessions).restart(account.getId());
    }

    @Test
    void sessionIsNotRestartedWhenTheLoginFails() {
        admin.reauth(account.getId());

        login.completeExceptionally(new IllegalStateException("rejected"));

        verify(sessions, never()).restart(any());
    }

    @Test
    void reauthWhileALoginRunsIsAConflict() {
        when(coordinator.startInteractive(any())).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("A login is already in progress")));

        assertThatThrownBy(() -> admin.reauth(account.getId()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void dtoNeverCarriesSecrets() throws Exception {
        account.setPasswordEnc("enc:hunter2");

        String json = new ObjectMapper().writeValueAsString(admin.list());

        assertThat(json).contains("Bot#1234").contains("\"sessionState\":\"READY\"")
                .doesNotContain("email", "password", "storageState", "hunter2", "bot-mailbox", "enc:state");
    }

    @Test
    void friendCountIsTheAcceptedLinks() {
        when(links.countByServiceAccountAndStatus(account, BattleNetFriendLink.Status.ACCEPTED)).thenReturn(3L);
        when(accounts.findAll()).thenReturn(List.of(account));

        assertThat(admin.list()).singleElement().extracting(AccountDto::friendCount).isEqualTo(3L);
    }

    @Test
    void codeIsForwardedToTheCoordinator() {
        admin.submitCode(account.getId(), "123456");

        verify(coordinator).submitCode(account.getId(), "123456");
    }

    @Test
    void codeWithNoFlowWaitingIsAConflict() {
        org.mockito.Mockito.doThrow(new IllegalStateException("none")).when(coordinator).submitCode(any(), any());

        assertThatThrownBy(() -> admin.submitCode(account.getId(), "123456"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void unknownAccountIsNotFound() {
        assertThatThrownBy(() -> admin.submitCode(UUID.randomUUID(), "1"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void deleteStopsTheSessionFirst() {
        BattleNetFriendLink link = new BattleNetFriendLink();
        when(links.findByServiceAccount(account)).thenReturn(List.of(link));

        admin.delete(account.getId());

        InOrder order = inOrder(sessions, links, accounts);
        order.verify(sessions).stop(account.getId());
        order.verify(links).deleteAll(List.of(link));
        order.verify(accounts).delete(account);
    }

    @Test
    void updatePasswordReEncrypts() {
        AccountDto dto = admin.update(account.getId(), Map.of("password", "n3w"));

        assertThat(account.getPasswordEnc()).isEqualTo("enc:n3w");
        assertThat(account.getAuthState()).isEqualTo(AuthState.NEW);
        verify(sessions).stop(account.getId());
        verify(coordinator).startInteractive(account.getId());
        assertThat(dto.authState()).isEqualTo("NEW");
    }

    @Test
    void updateEditsPlainFieldsWithoutLoggingIn() {
        admin.update(account.getId(),
                Map.of("label", "renamed", "fillOrder", 4, "friendLimitReached", false, "enabled", true));

        assertThat(account.getLabel()).isEqualTo("renamed");
        assertThat(account.getFillOrder()).isEqualTo(4);
        verify(coordinator, never()).startInteractive(any());
    }

    @Test
    void updateWithAWrongTypeIsABadRequest() {
        assertThatThrownBy(() -> admin.update(account.getId(), Map.of("enabled", "yes")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
