package fr.enimaloc.catapult.service.battlenet.login;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import fr.enimaloc.catapult.bgs.client.TicketException;
import fr.enimaloc.catapult.domain.battlenet.BattleNetServiceAccount;
import fr.enimaloc.catapult.domain.battlenet.BattleNetServiceAccount.AuthState;
import fr.enimaloc.catapult.repository.battlenet.BattleNetServiceAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.battlenet.login.BattleNetLoginBrowser.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BattleNetLoginCoordinatorTest {

    private static final String EMAIL = "bot-mailbox@example.com";
    private static final String PASSWORD = "hunter2-password";
    private static final String TICKET = "EU-0123456789abcdef-ticket";
    private static final String OLD_STATE = "{\"cookies\":[\"old\"]}";

    private final TokenEncryptionService encryption = new TokenEncryptionService("0123456789abcdef0123456789abcdef");
    private final BattleNetServiceAccountRepository repository = mock(BattleNetServiceAccountRepository.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<BattleNetLoginBrowser> browserProvider = mock(ObjectProvider.class);
    private final FakeLoginBrowser browser = new FakeLoginBrowser();
    private final BattleNetServiceAccount account = new BattleNetServiceAccount();
    private final Logger logger = (Logger) LoggerFactory.getLogger(BattleNetLoginCoordinator.class.getPackageName());
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private BattleNetLoginCoordinator coordinator;

    @BeforeEach
    void setUp() {
        account.setId(UUID.randomUUID());
        account.setLabel("bot-1");
        account.setEmail(EMAIL);
        account.setPasswordEnc(encryption.encrypt(PASSWORD));
        account.setStorageStateEnc(encryption.encrypt(OLD_STATE));
        when(repository.findById(account.getId())).thenReturn(Optional.of(account));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(browserProvider.getIfAvailable()).thenReturn(browser);
        coordinator = coordinator(Duration.ofMinutes(10));
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        coordinator.shutdown();
    }

    private BattleNetLoginCoordinator coordinator(Duration codeTimeout) {
        return new BattleNetLoginCoordinator(repository, encryption, browserProvider,
                TransactionOperations.withoutTransaction(), Duration.ofSeconds(60), codeTimeout);
    }

    @Test
    void ticketOnFirstTrySavesEncryptedStorageStateAndMarksReady() throws Exception {
        browser.script(new Outcome.Ticket(TICKET));

        coordinator.startInteractive(account.getId()).get(5, TimeUnit.SECONDS);

        assertThat(account.getAuthState()).isEqualTo(AuthState.READY);
        assertThat(account.getStorageStateEnc()).doesNotContain("fresh");
        assertThat(encryption.decrypt(account.getStorageStateEnc())).isEqualTo(browser.storageStateAfterLogin);
        assertThat(browser.lastPage().openedWith).isEqualTo(OLD_STATE);
        assertThat(browser.lastPage().credentials).containsExactly(EMAIL, PASSWORD);
        assertThat(browser.lastPage().closed).isTrue();
    }

    @Test
    void automaticReMintReturnsTheTicket() throws Exception {
        browser.script(new Outcome.Ticket(TICKET));

        assertThat(coordinator.ticketProviderFor(account.getId()).mint()).isEqualTo(TICKET);
        assertThat(account.getAuthState()).isEqualTo(AuthState.READY);
        assertThat(browser.lastPage().closed).isTrue();
    }

    @Test
    void codeRequiredInteractiveWaitsThenResumesWithTheCode() throws Exception {
        browser.script(new Outcome.CodeRequired(), new Outcome.Ticket(TICKET));

        CompletableFuture<Void> login = coordinator.startInteractive(account.getId());
        awaitWaiting();
        assertThat(account.getAuthState()).isEqualTo(AuthState.CODE_REQUIRED);
        coordinator.submitCode(account.getId(), "123456");
        login.get(5, TimeUnit.SECONDS);

        assertThat(browser.lastPage().codes).containsExactly("123456");
        assertThat(account.getAuthState()).isEqualTo(AuthState.READY);
        assertThat(coordinator.waitingForCode(account.getId())).isFalse();
    }

    @Test
    void codeNeverEnteredFailsAfterTenMinutes() {
        coordinator.shutdown();
        coordinator = coordinator(Duration.ofMillis(50));
        browser.script(new Outcome.CodeRequired());

        CompletableFuture<Void> login = coordinator.startInteractive(account.getId());

        assertThatThrownBy(() -> login.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(TicketException.class);
        assertThat(account.getAuthState()).isEqualTo(AuthState.AUTH_FAILED);
        assertThat(browser.lastPage().closed).isTrue();
    }

    @Test
    void interactiveFailureMarksAuthFailed() {
        browser.script(new Outcome.Failed("wrong password"));

        CompletableFuture<Void> login = coordinator.startInteractive(account.getId());

        assertThatThrownBy(() -> login.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(TicketException.class);
        assertThat(account.getAuthState()).isEqualTo(AuthState.AUTH_FAILED);
    }

    @Test
    void submitCodeWithoutAWaitingFlowIsRejected() {
        assertThatThrownBy(() -> coordinator.submitCode(account.getId(), "123456"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void automaticReMintNeedingACodeMarksCodeRequiredAndThrows() {
        browser.script(new Outcome.CodeRequired());

        assertThatThrownBy(() -> coordinator.ticketProviderFor(account.getId()).mint())
                .isInstanceOfSatisfying(TicketException.class, e -> assertThat(e.codeRequired()).isTrue());
        assertThat(account.getAuthState()).isEqualTo(AuthState.CODE_REQUIRED);
        assertThat(browser.lastPage().codes).isEmpty();
        assertThat(browser.lastPage().closed).isTrue();
    }

    @Test
    void reMintWhileAnInteractiveFlowWaitsForACodeFailsFast() throws Exception {
        browser.script(new Outcome.CodeRequired(), new Outcome.Ticket(TICKET));
        CompletableFuture<Void> login = coordinator.startInteractive(account.getId());
        awaitWaiting();

        assertThatThrownBy(() -> coordinator.ticketProviderFor(account.getId()).mint())
                .isInstanceOfSatisfying(TicketException.class, e -> assertThat(e.codeRequired()).isTrue());
        assertThat(browser.pages).hasSize(1);

        coordinator.submitCode(account.getId(), "123456");
        login.get(5, TimeUnit.SECONDS);
    }

    @Test
    void secondInteractiveLoginForTheSameAccountIsRejected() throws Exception {
        browser.script(new Outcome.CodeRequired(), new Outcome.Ticket(TICKET));
        CompletableFuture<Void> first = coordinator.startInteractive(account.getId());
        awaitWaiting();

        assertThatThrownBy(() -> coordinator.startInteractive(account.getId()).get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalStateException.class);

        coordinator.submitCode(account.getId(), "123456");
        first.get(5, TimeUnit.SECONDS);
    }

    @Test
    void missingBrowserFailsTheLoginWithAuthFailed() {
        when(browserProvider.getIfAvailable()).thenReturn(null);

        CompletableFuture<Void> login = coordinator.startInteractive(account.getId());

        assertThatThrownBy(() -> login.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(TicketException.class);
        assertThat(account.getAuthState()).isEqualTo(AuthState.AUTH_FAILED);
    }

    @Test
    void unknownAccountFailsTheLogin() {
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> coordinator.ticketProviderFor(unknown).mint())
                .isInstanceOfSatisfying(TicketException.class, e -> assertThat(e.codeRequired()).isFalse());
    }

    @Test
    void passwordAndTicketNeverLogged() throws Exception {
        browser.script(new Outcome.Ticket(TICKET), new Outcome.CodeRequired(), new Outcome.Failed("timeout"));
        coordinator.startInteractive(account.getId()).get(5, TimeUnit.SECONDS);
        List<Throwable> errors = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            try {
                coordinator.ticketProviderFor(account.getId()).mint();
            } catch (TicketException e) {
                errors.add(e);
            }
        }

        assertThat(logs.list).isNotEmpty();
        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : logs.list) {
            lines.add(event.getFormattedMessage());
            for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
                lines.add(t.getMessage());
            }
        }
        errors.forEach(e -> lines.add(e.getMessage()));
        assertThat(lines).isNotEmpty().allSatisfy(line -> assertThat(String.valueOf(line))
                .doesNotContain(PASSWORD).doesNotContain(TICKET).doesNotContain(EMAIL)
                .doesNotContain("cookies"));
    }

    private void awaitWaiting() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!coordinator.waitingForCode(account.getId())) {
            if (System.nanoTime() > deadline) throw new AssertionError("login never waited for a code");
            Thread.sleep(5);
        }
    }
}
