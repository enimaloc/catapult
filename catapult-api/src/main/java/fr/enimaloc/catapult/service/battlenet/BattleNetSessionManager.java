package fr.enimaloc.catapult.service.battlenet;

import fr.enimaloc.catapult.bgs.client.BgsClient;
import fr.enimaloc.catapult.bgs.client.BgsClient.BgsClientConfig;
import fr.enimaloc.catapult.bgs.client.BgsConnection;
import fr.enimaloc.catapult.bgs.client.BgsListener;
import fr.enimaloc.catapult.bgs.client.FriendEvent;
import fr.enimaloc.catapult.bgs.client.PresenceEvent;
import fr.enimaloc.catapult.bgs.core.BgsChannel;
import fr.enimaloc.catapult.bgs.core.BgsSession;
import fr.enimaloc.catapult.domain.battlenet.BattleNetServiceAccount;
import fr.enimaloc.catapult.domain.battlenet.BattleNetServiceAccount.AuthState;
import fr.enimaloc.catapult.repository.battlenet.BattleNetServiceAccountRepository;
import fr.enimaloc.catapult.service.battlenet.login.BattleNetLoginCoordinator;
import fr.enimaloc.catapult.site.SiteRole;
import fr.enimaloc.catapult.site.SiteRoleChangedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Keeps one BGS session per enabled and {@code READY} Battle.net service account (spec §10.1), and the game each
 * friend of the bots is playing, from their presence pushes.
 *
 * <p>Sessions open only when {@code battlenet.sessions.enabled=true} and this site is the active one: a second
 * session on the same account evicts the first one (ForceDisconnect 60, spec §8), so a standby site, a dev
 * environment or a restored dump must never open one. They close when the site goes standby and reopen when it
 * becomes active again.
 *
 * <p>The session callbacks run on the session loop: everything slow (database writes, friend event handling) is
 * handed to a single thread, which keeps the order in which the session reported the events.
 */
@Slf4j
@Service
@ConditionalOnBooleanProperty("battlenet.enabled")
public class BattleNetSessionManager implements SmartLifecycle {

    /** {@link #sessionState} of an account that has no session. */
    public static final String DISABLED = "DISABLED";

    static final int BGS_PORT = 1119;
    static final String LOCALE = "frFR";
    static final Duration MIN_BACKOFF = Duration.ofSeconds(5);
    static final Duration MAX_BACKOFF = Duration.ofSeconds(120);
    static final String REPLACED_COUNTER = "catapult.battlenet.session.replaced";
    static final String ADMIN_ACTION_COUNTER = "catapult.battlenet.session.admin_action_required";

    private final BattleNetServiceAccountRepository repository;
    private final BattleNetLoginCoordinator coordinator;
    private final SiteRole siteRole;
    private final ApplicationEventPublisher events;
    private final MeterRegistry registry;
    private final TransactionOperations transactions;
    private final boolean sessionsEnabled;
    private final String host;
    private final Supplier<BgsChannel> channels;
    private final ScheduledExecutorService scheduler;
    private final Executor starter;
    private final Executor mintExecutor;
    private final Executor handoff;
    private final List<ExecutorService> owned;

    private final Object lock = new Object();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    /** Game fourcc by BGS account id, for the friends of every open session. */
    private final Map<Long, String> games = new ConcurrentHashMap<>();
    private volatile boolean running;

    @Autowired
    public BattleNetSessionManager(BattleNetServiceAccountRepository repository,
                                   BattleNetLoginCoordinator coordinator,
                                   SiteRole siteRole,
                                   ApplicationEventPublisher events,
                                   MeterRegistry registry,
                                   TransactionOperations transactions,
                                   @Value("${battlenet.sessions.enabled:false}") boolean sessionsEnabled,
                                   @Value("${battlenet.bgs-host:eu.actual.battle.net}") String host) {
        this(repository, coordinator, siteRole, events, registry, transactions, sessionsEnabled, host,
                () -> connect(host),
                // Two threads: a connect blocking one (TCP + TLS) does not hold the keep-alives back
                Executors.newScheduledThreadPool(2, Thread.ofPlatform().name("battlenet-bgs-timers-", 0).daemon()
                        .factory()),
                null,
                Executors.newVirtualThreadPerTaskExecutor(),
                Executors.newSingleThreadExecutor(Thread.ofPlatform().name("battlenet-sessions").daemon().factory()));
    }

    /**
     * @param starter    runs the first connection of a session (a blocking TCP connect); the scheduler when null
     * @param handoff    runs, in order, what the session callbacks must not do on the session loop
     */
    BattleNetSessionManager(BattleNetServiceAccountRepository repository,
                            BattleNetLoginCoordinator coordinator,
                            SiteRole siteRole,
                            ApplicationEventPublisher events,
                            MeterRegistry registry,
                            TransactionOperations transactions,
                            boolean sessionsEnabled,
                            String host,
                            Supplier<BgsChannel> channels,
                            ScheduledExecutorService scheduler,
                            Executor starter,
                            Executor mintExecutor,
                            Executor handoff) {
        this.repository = repository;
        this.coordinator = coordinator;
        this.siteRole = siteRole;
        this.events = events;
        this.registry = registry;
        this.transactions = transactions;
        this.sessionsEnabled = sessionsEnabled;
        this.host = host;
        this.channels = channels;
        this.scheduler = scheduler;
        this.starter = starter != null ? starter : scheduler;
        this.mintExecutor = mintExecutor;
        this.handoff = handoff;
        this.owned = Stream.of(scheduler, mintExecutor, handoff)
                .filter(ExecutorService.class::isInstance).map(ExecutorService.class::cast).toList();
    }

    private static BgsChannel connect(String host) {
        try {
            return BgsSession.connect(host, BGS_PORT);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- lifecycle ---

    @Override
    public void start() {
        synchronized (lock) {
            running = true;
            if (!sessionsEnabled) {
                log.info("Battle.net BGS sessions are disabled (battlenet.sessions.enabled=false)");
                return;
            }
            openEligible();
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            running = false;
            closeAll();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @PreDestroy
    void shutdown() {
        owned.forEach(ExecutorService::shutdownNow);
    }

    /** Sessions follow the anti split-brain guard: open on the active site only. */
    @EventListener
    public void onSiteRoleChanged(SiteRoleChangedEvent event) {
        synchronized (lock) {
            if (event.isActive()) {
                openEligible();
            } else {
                if (!sessions.isEmpty()) {
                    log.info("Site went standby: closing the {} Battle.net BGS session(s)", sessions.size());
                }
                closeAll();
            }
        }
    }

    // --- per account ---

    /** Closes the account's session, if any, then reopens it when the account is still eligible. */
    public void restart(UUID accountId) {
        synchronized (lock) {
            close(accountId);
            if (!canOpen()) {
                return;
            }
            repository.findById(accountId)
                    .filter(account -> account.isEnabled() && account.getAuthState() == AuthState.READY)
                    .ifPresent(this::open);
        }
    }

    /** Closes the account's session for good, e.g. before the account is deleted. */
    public void stop(UUID accountId) {
        synchronized (lock) {
            close(accountId);
        }
    }

    /** The {@link BgsConnection.State} name of the account's session, or {@link #DISABLED} without one. */
    public String sessionState(UUID accountId) {
        Session session = sessions.get(accountId);
        return session == null ? DISABLED : session.connection.state().name();
    }

    /** The fourcc of the game the BGS account plays, as reported to the bot it is friends with. */
    public Optional<String> currentGame(long bnetAccountId) {
        return Optional.ofNullable(games.get(bnetAccountId));
    }

    /**
     * Runs {@code action} on the account's client. Fails at once, with an {@link IllegalStateException}, when the
     * account has no ready session: actions are never queued.
     */
    public <T> CompletableFuture<T> withClient(UUID accountId, Function<BgsClient, CompletableFuture<T>> action) {
        Session session = sessions.get(accountId);
        if (session == null || session.connection.state() != BgsConnection.State.READY || !session.client.ready()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("No ready BGS session for this Battle.net service account"));
        }
        try {
            return action.apply(session.client);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** The session callbacks of the account; for tests. */
    BgsListener listenerOf(UUID accountId) {
        return sessions.get(accountId);
    }

    // --- internals, called with the lock held ---

    private boolean canOpen() {
        return running && sessionsEnabled && siteRole.isActive();
    }

    private void openEligible() {
        if (!canOpen()) {
            return;
        }
        try {
            repository.findByEnabledTrueAndAuthStateOrderByFillOrderAsc(AuthState.READY).forEach(this::open);
        } catch (RuntimeException e) {
            log.warn("Could not list the Battle.net service accounts to open their sessions: {}", e.toString());
        }
    }

    private void open(BattleNetServiceAccount account) {
        if (sessions.containsKey(account.getId())) {
            return;
        }
        Session session = new Session(account);
        sessions.put(account.getId(), session);
        log.info("Opening the BGS session of Battle.net service account {}", account.getId());
        try {
            starter.execute(session::start);
        } catch (RejectedExecutionException e) {
            log.warn("Could not start the BGS session of {}: the executor is shut down", account.getId());
        }
    }

    private void close(UUID accountId) {
        Session session = sessions.remove(accountId);
        if (session != null) {
            log.info("Closing the BGS session of Battle.net service account {}", accountId);
            session.stop();
        }
    }

    private void closeAll() {
        List.copyOf(sessions.keySet()).forEach(this::close);
    }

    private void handOff(String what, Runnable task) {
        try {
            handoff.execute(task);
        } catch (RejectedExecutionException e) {
            log.debug("Dropped {}: the executor is shut down", what);
        }
    }

    private void storeIdentity(UUID accountId, long bnetAccountId, String battleTag) {
        try {
            transactions.executeWithoutResult(status -> repository.findById(accountId).ifPresent(account -> {
                if (Objects.equals(account.getBnetAccountId(), bnetAccountId)
                        && Objects.equals(account.getBattleTag(), battleTag)) {
                    return;
                }
                account.setBnetAccountId(bnetAccountId);
                account.setBattleTag(battleTag);
                repository.save(account);
            }));
        } catch (RuntimeException e) {
            log.warn("Could not store the BattleTag of Battle.net service account {}: {}", accountId, e.toString());
        }
    }

    /** One account's connection and its callbacks. */
    private final class Session implements BgsListener {

        private final UUID accountId;
        private final BgsClient client;
        private final BgsConnection connection;
        /** Friends whose game this session reported, forgotten when it ends. */
        private final Set<Long> watched = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean stopped = new AtomicBoolean();

        Session(BattleNetServiceAccount account) {
            this.accountId = account.getId();
            this.client = new BgsClient(new BgsClientConfig(host, BGS_PORT, account.getEmail(), LOCALE),
                    coordinator.ticketProviderFor(accountId), this, mintExecutor);
            this.connection = new BgsConnection(client, channels, scheduler, MIN_BACKOFF, MAX_BACKOFF);
        }

        void start() {
            connection.start();
            // A stop that raced the start found nothing to stop yet
            if (stopped.get()) {
                connection.stop();
            }
        }

        void stop() {
            stopped.set(true);
            connection.stop();
            forgetGames();
        }

        private boolean current() {
            return sessions.get(accountId) == this;
        }

        private void forgetGames() {
            watched.forEach(games::remove);
            watched.clear();
        }

        @Override
        public void onReady(long bnetAccountId, String battleTag) {
            log.info("BGS session of Battle.net service account {} is ready as {}", accountId, battleTag);
            handOff("the BattleTag of " + accountId, () -> storeIdentity(accountId, bnetAccountId, battleTag));
        }

        @Override
        public void onPresence(PresenceEvent event) {
            if (!current()) {
                return;
            }
            watched.add(event.accountId());
            if (event.game() == null) {
                games.remove(event.accountId());
            } else {
                games.put(event.accountId(), event.game());
            }
        }

        @Override
        public void onFriend(FriendEvent event) {
            handOff("a friend event of " + accountId,
                    () -> events.publishEvent(new BattleNetFriendEventReceived(accountId, event)));
        }

        @Override
        public void onClosed(CloseReason reason) {
            // The next session pushes the whole presence again
            if (current()) {
                forgetGames();
            }
            switch (reason) {
                case SESSION_REPLACED -> {
                    log.error("BGS session of Battle.net service account {} was replaced by another login on the "
                            + "same account: not reconnecting", accountId);
                    registry.counter(REPLACED_COUNTER, "account", accountId.toString()).increment();
                }
                case CODE_REQUIRED -> {
                    log.warn("BGS session of Battle.net service account {} stopped: the login needs an admin "
                            + "(2FA code, rejected login or repeated timeouts)", accountId);
                    registry.counter(ADMIN_ACTION_COUNTER, "account", accountId.toString()).increment();
                }
                default -> log.debug("BGS session of Battle.net service account {} closed: {}", accountId, reason);
            }
        }
    }
}
