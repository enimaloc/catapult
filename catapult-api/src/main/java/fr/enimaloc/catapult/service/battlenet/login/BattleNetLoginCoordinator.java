package fr.enimaloc.catapult.service.battlenet.login;

import fr.enimaloc.catapult.bgs.client.TicketException;
import fr.enimaloc.catapult.bgs.client.TicketProvider;
import fr.enimaloc.catapult.domain.battlenet.BattleNetServiceAccount.AuthState;
import fr.enimaloc.catapult.repository.battlenet.BattleNetServiceAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.battlenet.login.BattleNetLoginBrowser.LoginPage;
import fr.enimaloc.catapult.service.battlenet.login.BattleNetLoginFlow.Result;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs the Battle.net logins of the service accounts (spec §9, §9.1) one at a time on a dedicated thread,
 * so that a single Chromium is ever open. Admin logins may wait on the code page for a code entered in
 * Catapult; automatic re-mints never wait and mark the account {@code CODE_REQUIRED} instead.
 */
@Slf4j
@Service
@ConditionalOnBooleanProperty("battlenet.enabled")
public class BattleNetLoginCoordinator {

    private final BattleNetServiceAccountRepository repository;
    private final TokenEncryptionService encryption;
    private final ObjectProvider<BattleNetLoginBrowser> browsers;
    private final TransactionOperations transactions;
    private final Duration ticketTimeout;
    private final Duration codeTimeout;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("battlenet-login").daemon().factory());
    /** Admin logins from submission to completion, per service account. */
    private final Map<UUID, BattleNetLoginFlow> interactiveFlows = new ConcurrentHashMap<>();
    private final int maxConsecutiveTimeouts;
    /** Consecutive automatic re-mints that ended in a timeout, per service account (in memory). */
    private final Map<UUID, Integer> timeouts = new ConcurrentHashMap<>();

    public BattleNetLoginCoordinator(BattleNetServiceAccountRepository repository,
                                     TokenEncryptionService encryption,
                                     ObjectProvider<BattleNetLoginBrowser> browsers,
                                     TransactionOperations transactions,
                                     @Value("${battlenet.login.ticket-timeout:60s}") Duration ticketTimeout,
                                     @Value("${battlenet.login.code-timeout:10m}") Duration codeTimeout,
                                     @Value("${battlenet.login.max-consecutive-timeouts:3}")
                                     int maxConsecutiveTimeouts) {
        this.repository = repository;
        this.encryption = encryption;
        this.browsers = browsers;
        this.transactions = transactions;
        this.ticketTimeout = ticketTimeout;
        this.codeTimeout = codeTimeout;
        this.maxConsecutiveTimeouts = maxConsecutiveTimeouts;
    }

    /**
     * Admin login: completes once the account is {@code READY}, or fails with a {@link TicketException}
     * (account then {@code AUTH_FAILED}). While the page asks for a code the account is {@code CODE_REQUIRED}
     * and {@link #submitCode} resumes it.
     */
    public CompletableFuture<Void> startInteractive(UUID accountId) {
        timeouts.remove(accountId);
        BattleNetLoginFlow flow = new BattleNetLoginFlow(true, ticketTimeout, codeTimeout,
                () -> {
                    setAuthState(accountId, AuthState.CODE_REQUIRED);
                    log.info("Battle.net login of service account {} waits for a 2FA code", accountId);
                });
        if (interactiveFlows.putIfAbsent(accountId, flow) != null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("A login is already in progress for this account"));
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                Throwable failure = null;
                try {
                    login(accountId, flow);
                } catch (TicketException | RuntimeException e) {
                    failure = e;
                } finally {
                    interactiveFlows.remove(accountId, flow);
                }
                if (failure == null) done.complete(null);
                else done.completeExceptionally(failure);
            });
        } catch (RejectedExecutionException e) {
            interactiveFlows.remove(accountId, flow);
            done.completeExceptionally(new IllegalStateException("The Battle.net login executor is shut down"));
        }
        return done;
    }

    /** Fills the code page of the account's admin login; rejected when no login waits for a code. */
    public void submitCode(UUID accountId, String code) {
        BattleNetLoginFlow flow = interactiveFlows.get(accountId);
        if (flow == null) throw new IllegalStateException("No login is waiting for a code for this account");
        flow.submitCode(code);
    }

    public boolean waitingForCode(UUID accountId) {
        BattleNetLoginFlow flow = interactiveFlows.get(accountId);
        return flow != null && flow.waitingForCode();
    }

    /**
     * Automatic re-mint for the account's BGS session: blocking, never waits for a code. Failures that need an
     * admin throw {@link TicketException} with {@code codeRequired=true}, so the connection stops instead of
     * retrying: a code request ({@code CODE_REQUIRED}) and a login turned down by Battle.net, such as a wrong
     * password or a locked account ({@code AUTH_FAILED}; repeated failed logins could get the bot locked).
     * Timeouts and technical failures (browser crash, navigation error, no browser bean, unreadable stored
     * credentials) leave the auth state unchanged and throw with {@code codeRequired=false}: retryable. Except
     * that a login page which never answers may be an undetected rejection: after
     * {@code maxConsecutiveTimeouts} (default 3) automatic re-mints in a row ended in a timeout, the account
     * becomes {@code AUTH_FAILED} and the exception has {@code codeRequired=true}. A ticket or an admin login
     * resets the count; technical failures do not count.
     */
    public TicketProvider ticketProviderFor(UUID accountId) {
        return () -> mint(accountId);
    }

    private String mint(UUID accountId) throws TicketException {
        BattleNetLoginFlow running = interactiveFlows.get(accountId);
        if (running != null) {
            // Queuing behind an admin login that waits for a code would block for up to codeTimeout.
            boolean codeRequired = running.waitingForCode();
            throw new TicketException(codeRequired ? "An admin login waits for a 2FA code"
                    : "An admin login is in progress", codeRequired);
        }
        BattleNetLoginFlow flow = new BattleNetLoginFlow(false, ticketTimeout, codeTimeout, () -> { });
        Future<String> ticket;
        try {
            ticket = executor.submit(() -> login(accountId, flow));
        } catch (RejectedExecutionException e) {
            throw new TicketException("The Battle.net login executor is shut down", false);
        }
        // Another account's admin login may hold the executor while it waits for its code.
        Duration maxWait = codeTimeout.plus(ticketTimeout.multipliedBy(3));
        try {
            return ticket.get(maxWait.toNanos(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TicketException te) throw te;
            throw new TicketException("Battle.net login failed (" + e.getCause().getClass().getSimpleName() + ")",
                    false);
        } catch (TimeoutException e) {
            ticket.cancel(true);
            throw new TicketException("Timed out waiting for the Battle.net login", false);
        } catch (InterruptedException e) {
            ticket.cancel(true);
            Thread.currentThread().interrupt();
            throw new TicketException("Interrupted while waiting for the Battle.net login", false);
        }
    }

    /** Runs on the login thread: one attempt, then the resulting auth state is persisted. */
    private String login(UUID accountId, BattleNetLoginFlow flow) throws TicketException {
        Credentials credentials;
        try {
            credentials = transactions.execute(status -> repository.findById(accountId)
                    .map(account -> new Credentials(account.getEmail(), encryption.decrypt(account.getPasswordEnc()),
                            account.getStorageStateEnc() == null ? null
                                    : encryption.decrypt(account.getStorageStateEnc())))
                    .orElse(null));
        } catch (RuntimeException e) {
            return fail(accountId, flow, "Could not read the stored credentials (" + e.getClass().getSimpleName() + ")",
                    flow.interactive());
        }
        if (credentials == null) throw new TicketException("Unknown Battle.net service account", false);

        BattleNetLoginBrowser browser = browsers.getIfAvailable();
        if (browser == null) {
            return fail(accountId, flow, "No Battle.net login browser is available", flow.interactive());
        }

        Result result = attempt(browser, flow, credentials);
        return switch (result) {
            case Result.Success success -> {
                timeouts.remove(accountId);
                saveSuccess(accountId, success.storageState());
                log.info("Battle.net service account {} logged in", accountId);
                yield success.ticket();
            }
            case Result.CodeRequired ignored -> {
                setAuthState(accountId, AuthState.CODE_REQUIRED);
                log.warn("Battle.net service account {} asks for a 2FA code: an admin must log it in", accountId);
                throw new TicketException("The Battle.net login asks for a 2FA code", true);
            }
            case Result.Rejected rejected -> {
                setAuthState(accountId, AuthState.AUTH_FAILED);
                log.warn("Battle.net turned down the login of service account {} ({} login): {}", accountId,
                        flow.interactive() ? "admin" : "automatic", rejected.reason());
                // codeRequired: an admin must act; retrying the same credentials may get the account locked.
                throw new TicketException("Battle.net rejected the login: " + rejected.reason(), true);
            }
            case Result.Failed failed -> {
                if (!flow.interactive() && BattleNetLoginFlow.TIMEOUT_REASON.equals(failed.reason())
                        && timeouts.merge(accountId, 1, Integer::sum) >= maxConsecutiveTimeouts) {
                    timeouts.remove(accountId);
                    setAuthState(accountId, AuthState.AUTH_FAILED);
                    log.warn("Battle.net service account {}: {} automatic logins in a row timed out, "
                            + "an admin must log it in", accountId, maxConsecutiveTimeouts);
                    throw new TicketException("Battle.net login timed out repeatedly", true);
                }
                yield fail(accountId, flow, failed.reason(), flow.interactive());
            }
        };
    }

    private Result attempt(BattleNetLoginBrowser browser, BattleNetLoginFlow flow, Credentials credentials) {
        LoginPage page;
        try {
            page = browser.open(credentials.storageState());
        } catch (RuntimeException e) {
            return new Result.Failed("Could not open the login page (" + e.getClass().getSimpleName() + ")");
        }
        try {
            return flow.run(page, credentials.email(), credentials.password());
        } finally {
            try {
                page.close();
            } catch (RuntimeException e) {
                log.warn("Could not close the Battle.net login page ({})", e.getClass().getSimpleName());
            }
        }
    }

    private String fail(UUID accountId, BattleNetLoginFlow flow, String reason, boolean markFailed)
            throws TicketException {
        if (markFailed) setAuthState(accountId, AuthState.AUTH_FAILED);
        log.warn("Battle.net login of service account {} failed ({} login): {}", accountId,
                flow.interactive() ? "admin" : "automatic", reason);
        throw new TicketException("Battle.net login failed: " + reason, false);
    }

    private void saveSuccess(UUID accountId, @Nullable String storageState) {
        @Nullable String encrypted = storageState == null ? null : encryption.encrypt(storageState);
        transactions.executeWithoutResult(status -> repository.findById(accountId).ifPresent(account -> {
            if (encrypted != null) account.setStorageStateEnc(encrypted);
            account.setAuthState(AuthState.READY);
            repository.save(account);
        }));
    }

    private void setAuthState(UUID accountId, AuthState state) {
        transactions.executeWithoutResult(status -> repository.findById(accountId).ifPresent(account -> {
            account.setAuthState(state);
            repository.save(account);
        }));
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private record Credentials(String email, String password, @Nullable String storageState) {
        @Override
        public String toString() {
            return "Credentials[***]";
        }
    }
}
