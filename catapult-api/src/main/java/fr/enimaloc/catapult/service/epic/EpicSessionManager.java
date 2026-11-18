package fr.enimaloc.catapult.service.epic;

import fr.enimaloc.catapult.domain.epic.EpicServiceAccount;
import fr.enimaloc.catapult.domain.epic.EpicServiceAccount.AuthState;
import fr.enimaloc.catapult.repository.epic.EpicServiceAccountRepository;
import fr.enimaloc.catapult.service.debug.BatchDebugFilter;
import fr.enimaloc.catapult.service.debug.SessionDebug;
import fr.enimaloc.catapult.service.epic.EpicTokenService.AccessToken;
import fr.enimaloc.catapult.service.epic.api.EpicAuthException;
import fr.enimaloc.catapult.service.epic.api.EpicFriendsClient;
import fr.enimaloc.catapult.service.epic.api.EpicPresenceClient;
import fr.enimaloc.catapult.service.epic.connect.EpicConnectClient;
import fr.enimaloc.catapult.service.epic.connect.EpicConnectListener;
import fr.enimaloc.catapult.service.epic.connect.EpicConnectMessage;
import fr.enimaloc.catapult.site.SiteRole;
import fr.enimaloc.catapult.site.SiteRoleChangedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Keeps one Epic Connect session per enabled and {@code READY} Epic bot (spec §4.4), and the game each friend of the
 * bots is playing (spec §3).
 *
 * <p>Sessions open only when {@code epic.sessions.enabled=true} and this site is the active one, like the Ankama
 * ones: a standby site, a dev environment or a restored dump must never use the bots, nor rotate their refresh
 * tokens. They close when the site goes standby and reopen when it becomes active again.
 *
 * <p>A connection: access token ({@link EpicTokenService}) → Epic Connect → {@code core.connect.v1.connected} →
 * presence registration of the connection (the server then pushes each friend's presence) → friends listing, published
 * as an {@link EpicFriendEventReceived}. The friends are listed again on every {@code friends.v1.*} notification and
 * every {@link #RESYNC_PERIOD}. Each connection is replaced {@link #RENEW_BEFORE_EXPIRY} before its access token
 * expires. A refused refresh token stops the session until the admin logs the bot in again; a rejected access token
 * is evicted and the connection retried with a fresh one, at once the first time, then with the usual backoff.
 *
 * <p>When a connection drops, every game it reported is forgotten at once: the next connection pushes the presences
 * again. A planned renewal keeps them, as stale, so that a streamer's game does not vanish while the new connection
 * opens: they are forgotten if the new connection fails, and those it has not reported again
 * {@link #STALE_GRACE} after it registered.
 */
@Slf4j
@Service
@ConditionalOnBooleanProperty("epic.enabled")
public class EpicSessionManager implements SmartLifecycle {

    /** {@link #sessionState} of an account that has no session. */
    public static final String DISABLED = "DISABLED";

    /** States of an open session, as {@link #sessionState} names them. */
    enum State { CONNECTING, READY, BACKOFF }

    static final Duration HEARTBEAT = Duration.ofSeconds(30);
    static final Duration MIN_BACKOFF = Duration.ofSeconds(5);
    /** The shortest delay before a planned renewal, whatever the access token's lifetime. */
    static final Duration RENEWAL_FLOOR = Duration.ofSeconds(60);
    /**
     * After a planned renewal, how long the games the previous connection reported stay known once the new connection
     * is registered, waiting for it to report them again.
     */
    static final Duration STALE_GRACE = Duration.ofSeconds(30);
    static final Duration MAX_BACKOFF = Duration.ofSeconds(120);
    static final Duration RESYNC_PERIOD = Duration.ofMinutes(5);
    /** A connection is replaced this long before its access token expires (spec §4.4). */
    static final Duration RENEW_BEFORE_EXPIRY = EpicTokenService.MIN_REMAINING;
    /** An open connection that is not registered by then is replaced. */
    static final Duration REGISTER_TIMEOUT = Duration.ofSeconds(30);
    static final String PRESENCE_UPDATE = "presence.v1.UPDATE";
    static final String FRIENDS_PREFIX = "friends.v1.";
    /** Names the friends listings in the debug recording. */
    static final String FRIENDS_OPERATION = "epic.friends.summary";
    static final String ADMIN_ACTION_COUNTER = "catapult.epic.session.admin_action_required";
    static final String DROPPED_COUNTER = "catapult.epic.session.dropped";

    private final EpicServiceAccountRepository repository;
    private final EpicTokenService tokens;
    private final EpicFriendsClient friends;
    private final EpicPresenceClient presence;
    private final SiteRole siteRole;
    private final ApplicationEventPublisher events;
    private final MeterRegistry registry;
    private final SessionDebug debug;
    private final boolean sessionsEnabled;
    private final Supplier<EpicConnectClient> clients;
    private final ScheduledExecutorService scheduler;
    private final Executor starter;
    private final Executor handoff;
    private final Clock clock;
    private final Timing timing;
    private final List<ExecutorService> owned;

    private final Object lock = new Object();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    /** The game of each friend of the bots, by Epic account id, as the open connections reported it. */
    private final Map<String, EpicGame> games = new ConcurrentHashMap<>();
    private volatile boolean running;
    private ScheduledFuture<?> resyncTask;

    @Autowired
    public EpicSessionManager(EpicServiceAccountRepository repository,
                              EpicTokenService tokens,
                              EpicFriendsClient friends,
                              EpicPresenceClient presence,
                              SiteRole siteRole,
                              ApplicationEventPublisher events,
                              MeterRegistry registry,
                              @Qualifier("epicSessionDebug") SessionDebug debug,
                              @Value("${epic.sessions.enabled:false}") boolean sessionsEnabled,
                              @Value("${epic.connect-url:wss://connect.ol.epicgames.com}") String connectUrl) {
        this(repository, tokens, friends, presence, siteRole, events, registry, debug, sessionsEnabled,
                URI.create(connectUrl),
                Executors.newScheduledThreadPool(2, Thread.ofPlatform().name("epic-connect-timers-", 0).daemon()
                        .factory()));
    }

    private EpicSessionManager(EpicServiceAccountRepository repository,
                               EpicTokenService tokens,
                               EpicFriendsClient friends,
                               EpicPresenceClient presence,
                               SiteRole siteRole,
                               ApplicationEventPublisher events,
                               MeterRegistry registry,
                               SessionDebug debug,
                               boolean sessionsEnabled,
                               URI connectUrl,
                               ScheduledExecutorService scheduler) {
        this(repository, tokens, friends, presence, siteRole, events, registry, debug, sessionsEnabled,
                webSocketClients(connectUrl, scheduler),
                scheduler,
                // Connecting and the REST calls block on HTTP: never on the timers nor the WebSocket readers
                Executors.newVirtualThreadPerTaskExecutor(),
                Executors.newSingleThreadExecutor(Thread.ofPlatform().name("epic-sessions").daemon().factory()),
                Clock.systemUTC(),
                Timing.DEFAULT);
    }

    /**
     * @param clients    builds the single-use Epic Connect client of each connection
     * @param starter    runs each connection attempt and REST call (blocking)
     * @param handoff    runs, in order, the friend events publication
     * @param timing     the backoff, renewal and stale-game delays
     */
    EpicSessionManager(EpicServiceAccountRepository repository,
                       EpicTokenService tokens,
                       EpicFriendsClient friends,
                       EpicPresenceClient presence,
                       SiteRole siteRole,
                       ApplicationEventPublisher events,
                       MeterRegistry registry,
                       SessionDebug debug,
                       boolean sessionsEnabled,
                       Supplier<EpicConnectClient> clients,
                       ScheduledExecutorService scheduler,
                       Executor starter,
                       Executor handoff,
                       Clock clock,
                       Timing timing) {
        this.repository = repository;
        this.tokens = tokens;
        this.friends = friends;
        this.presence = presence;
        this.siteRole = siteRole;
        this.events = events;
        this.registry = registry;
        this.debug = debug;
        this.sessionsEnabled = sessionsEnabled;
        this.clients = clients;
        this.scheduler = scheduler;
        this.starter = starter;
        this.handoff = handoff;
        this.clock = clock;
        this.timing = timing;
        this.owned = Stream.of(scheduler, starter, handoff)
                .filter(ExecutorService.class::isInstance).map(ExecutorService.class::cast).toList();
    }

    /**
     * @param minBackoff   first delay before a reconnection, doubled up to {@link #MAX_BACKOFF}
     * @param renewalFloor shortest delay before a planned renewal
     * @param staleGrace   how long the games of a renewed connection wait to be reported again
     */
    record Timing(Duration minBackoff, Duration renewalFloor, Duration staleGrace) {
        static final Timing DEFAULT = new Timing(MIN_BACKOFF, RENEWAL_FLOOR, STALE_GRACE);
    }

    private static Supplier<EpicConnectClient> webSocketClients(URI connectUrl, ScheduledExecutorService scheduler) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        EpicConnectClient.Connector connector = (uri, listener) -> http.newWebSocketBuilder().buildAsync(uri, listener);
        return () -> new EpicConnectClient(connector, connectUrl, HEARTBEAT, scheduler);
    }

    // --- lifecycle ---

    @Override
    public void start() {
        synchronized (lock) {
            running = true;
            if (!sessionsEnabled) {
                log.info("Epic Connect sessions are disabled (epic.sessions.enabled=false)");
                return;
            }
            if (resyncTask == null) {
                try {
                    resyncTask = scheduler.scheduleWithFixedDelay(this::resyncAll, RESYNC_PERIOD.toMillis(),
                            RESYNC_PERIOD.toMillis(), TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException e) {
                    log.warn("Could not schedule the Epic friends resyncs: the executor is shut down");
                }
            }
            openEligible();
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            running = false;
            if (resyncTask != null) {
                resyncTask.cancel(false);
                resyncTask = null;
            }
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
                    log.info("Site went standby: closing the {} Epic Connect session(s)", sessions.size());
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
            repository.findById(accountId).filter(EpicSessionManager::eligible).ifPresent(this::open);
        }
    }

    /** Closes the account's session for good, e.g. before the account is deleted. */
    public void stop(UUID accountId) {
        synchronized (lock) {
            close(accountId);
        }
    }

    /** {@code CONNECTING}, {@code READY} or {@code BACKOFF}, or {@link #DISABLED} when the account has no session. */
    public String sessionState(UUID accountId) {
        Session session = sessions.get(accountId);
        return session == null ? DISABLED : session.state.name();
    }

    /** How many sessions are READY: the bots connected to Epic Connect right now (a gauge reads it). */
    public long readySessionCount() {
        return sessions.values().stream().filter(s -> s.state == State.READY).count();
    }

    /** The game the Epic account plays, as reported to the bot it is friends with. */
    public Optional<EpicGame> currentGame(String epicAccountId) {
        return Optional.ofNullable(games.get(epicAccountId));
    }

    /**
     * The access token of a bot whose connection is open and registered, for a read-only call such as the catalog
     * (spec §5.2). Never refreshes a token for this: empty when no session is open.
     */
    public Optional<String> anyOpenSessionToken() {
        return sessions.values().stream().map(Session::openToken).flatMap(Optional::stream).findFirst();
    }

    /**
     * Whether this instance may use the bots: sessions enabled and this site active. Anything that would call Epic
     * with a bot's token, hence possibly rotate its refresh token, checks it first.
     */
    public boolean canOpenSessions() {
        return canOpen();
    }

    /** Lists the friends of every registered connection again; runs every {@link #RESYNC_PERIOD}. */
    void resyncAll() {
        if (!canOpen()) {
            return;
        }
        sessions.values().forEach(Session::resyncLater);
    }

    // --- internals ---

    private static boolean eligible(EpicServiceAccount account) {
        return account.isEnabled() && account.getAuthState() == AuthState.READY;
    }

    private boolean canOpen() {
        return running && sessionsEnabled && siteRole.isActive();
    }

    /** Called with the lock held. */
    private void openEligible() {
        if (!canOpen()) {
            return;
        }
        try {
            repository.findByEnabledTrueAndAuthStateOrderByFillOrderAsc(AuthState.READY).forEach(this::open);
        } catch (RuntimeException e) {
            log.warn("Could not list the Epic service accounts to open their sessions: {}", e.toString());
        }
    }

    /** Called with the lock held. */
    private void open(EpicServiceAccount account) {
        if (sessions.containsKey(account.getId())) {
            return;
        }
        Session session = new Session(account.getId());
        sessions.put(account.getId(), session);
        log.info("Opening the Epic Connect session of Epic service account {}", account.getId());
        session.launch();
    }

    /**
     * Called with the lock held. The connection's callbacks never take this lock, so closing the client here (which
     * waits for a callback in flight) cannot deadlock.
     */
    private void close(UUID accountId) {
        Session session = sessions.remove(accountId);
        if (session != null) {
            log.info("Closing the Epic Connect session of Epic service account {}", accountId);
            session.stop();
        }
    }

    private void closeAll() {
        List.copyOf(sessions.keySet()).forEach(this::close);
    }

    private void execute(Executor executor, String what, Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            log.debug("Dropped {}: the executor is shut down", what);
        }
    }

    private ScheduledFuture<?> schedule(String what, Runnable task, Duration delay) {
        try {
            return scheduler.schedule(() -> execute(starter, what, task), delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            log.debug("Not scheduling {}: the executor is shut down", what);
            return null;
        }
    }

    /** One account's successive connections and the games they reported. */
    private final class Session {

        private final UUID accountId;
        /** Serializes the friends listings, so that their snapshots are published in order. */
        private final Object resyncLock = new Object();
        /** Games by Epic account id, as this session's current connection reported them. Guarded by this. */
        private final Map<String, EpicGame> reported = new HashMap<>();
        /**
         * Games reported by the connection a planned renewal replaced: still in {@code games} until the new
         * connection reports them again or {@link Timing#staleGrace} after it registered. Guarded by this.
         */
        private final Map<String, EpicGame> stale = new HashMap<>();
        private volatile State state = State.CONNECTING;
        private volatile boolean stopped;
        /** The connection in progress or open; null between two. Guarded by this. */
        private Link link;
        private ScheduledFuture<?> retry;
        private Duration backoff = timing.minBackoff();
        /** Access tokens rejected since the last settled connection. Guarded by this. */
        private int accessRejections;

        Session(UUID accountId) {
            this.accountId = accountId;
        }

        void launch() {
            execute(starter, "the Epic Connect session of " + accountId, this::attempt);
        }

        /** Called with the manager's lock held: closes the connection without holding this session's monitor. */
        void stop() {
            Link open;
            synchronized (this) {
                stopped = true;
                if (retry != null) {
                    retry.cancel(false);
                }
                open = link;
                link = null;
                forgetGames();
            }
            if (open != null) {
                open.end();
            }
        }

        synchronized Optional<String> openToken() {
            if (stopped || link == null || !link.registered
                    || !link.token.expiresAt().isAfter(clock.instant())) {
                return Optional.empty();
            }
            return Optional.of(link.token.value());
        }

        void resyncLater() {
            Link current;
            synchronized (this) {
                current = link != null && link.registered && !stopped ? link : null;
            }
            if (current != null) {
                execute(starter, "a friends resync of " + accountId, () -> resync(current, false));
            }
        }

        private boolean current() {
            return sessions.get(accountId) == this;
        }

        /** Whether this session still owns the account: neither stopped nor replaced. */
        private boolean owned() {
            return !stopped && current();
        }

        /** Leaves the session for good, from the session itself. */
        private void end() {
            synchronized (lock) {
                if (current()) {
                    close(accountId);
                }
            }
        }

        /** Counts the account and ends this session until the admin logs the bot in again. */
        private void stopForAdmin() {
            synchronized (lock) {
                if (!owned()) {
                    return;
                }
                log.warn("Epic refused the refresh token of service account {}: its session stops until it is "
                        + "logged in again", accountId);
                registry.counter(ADMIN_ACTION_COUNTER, "account", accountId.toString()).increment();
                close(accountId);
            }
        }

        private void attempt() {
            if (stopped) {
                return;
            }
            state = State.CONNECTING;
            EpicServiceAccount account;
            try {
                account = repository.findById(accountId).orElse(null);
            } catch (RuntimeException e) {
                log.warn("Could not read Epic service account {}: {}", accountId, e.toString());
                retryLater();
                return;
            }
            if (account == null || !eligible(account)) {
                log.info("Epic service account {} is no longer enabled and ready: closing its session", accountId);
                end();
                return;
            }
            if (!owned()) {
                // Stopped or replaced while reading: never refresh a token for a session that is gone
                return;
            }
            AccessToken token;
            try {
                token = tokens.token(accountId);
            } catch (EpicAuthException e) {
                if (e.reason() == EpicAuthException.Reason.REFRESH_REFUSED) {
                    stopForAdmin();
                } else {
                    log.warn("Could not get an access token for Epic service account {}: {}", accountId, e.reason());
                    retryLater();
                }
                return;
            } catch (RuntimeException e) {
                log.warn("Could not get an access token for Epic service account {}: {}", accountId,
                        e.getClass().getName());
                retryLater();
                return;
            }
            Link candidate = new Link(clients.get(), token, account.getEpicAccountId());
            synchronized (this) {
                if (stopped) {
                    return;
                }
                link = candidate;
            }
            try {
                candidate.client.connect(token.value(), candidate.botAccountId, candidate);
            } catch (RuntimeException e) {
                forget(candidate);
                if (stopped) {
                    // stop() closed the client while it was connecting
                    return;
                }
                // EpicConnectException carries no URL, hence no token
                log.warn("Could not connect Epic service account {} to Epic Connect: {}", accountId, e.toString());
                retryLater();
                return;
            }
            candidate.startTimers();
        }

        private synchronized void forget(Link candidate) {
            if (link == candidate) {
                link = null;
            }
        }

        private void retryLater() {
            synchronized (this) {
                if (stopped) {
                    return;
                }
                // A renewal that could not connect: the games it kept are no longer known
                forgetGames();
                state = State.BACKOFF;
                Duration delay = backoff;
                Duration doubled = backoff.multipliedBy(2);
                backoff = doubled.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : doubled;
                retry = schedule("a reconnection of " + accountId, this::attempt, delay);
            }
        }

        /**
         * Leaves {@code from} for a new connection, at once or after the backoff; does nothing when {@code from} is
         * no longer this session's connection. Never called from a connection callback.
         */
        private void reconnect(Link from, boolean immediate) {
            reconnect(from, immediate, false);
        }

        /**
         * @param planned a renewal: the games {@code from} reported stay known, as stale, until the new connection
         *                reports them again (see {@link #stale}); otherwise they are forgotten at once
         */
        private void reconnect(Link from, boolean immediate, boolean planned) {
            synchronized (this) {
                if (link != from || stopped) {
                    return;
                }
                link = null;
                state = State.CONNECTING;
                if (planned) {
                    stale.putAll(reported);
                    reported.clear();
                } else {
                    forgetGames();
                }
            }
            from.end();
            if (immediate) {
                launch();
            } else {
                retryLater();
            }
        }

        /** The connection registered and listed its friends once: the next failure starts the backoff afresh. */
        private synchronized void settle(Link from) {
            if (link == from) {
                backoff = timing.minBackoff();
                accessRejections = 0;
            }
        }

        /** Epic rejected the connection's access token: a fresh one, at once the first time, then with a backoff. */
        private void accessRejected(Link from) {
            boolean immediate;
            synchronized (this) {
                if (link != from || stopped) {
                    return;
                }
                immediate = accessRejections++ == 0;
            }
            log.warn("Epic rejected the access token of service account {}: reconnecting with a fresh one",
                    accountId);
            tokens.evict(accountId);
            reconnect(from, immediate);
        }

        private void register(Link from, String connectionId) {
            if (!from.live()) {
                return;
            }
            try {
                presence.register(from.token.value(), from.botAccountId, connectionId);
            } catch (EpicAuthException e) {
                accessRejected(from);
                return;
            } catch (RuntimeException e) {
                log.warn("Could not register the Epic Connect connection of service account {}: {}", accountId,
                        e.toString());
                reconnect(from, false);
                return;
            }
            synchronized (this) {
                if (link != from || stopped) {
                    return;
                }
                from.registered = true;
                state = State.READY;
            }
            log.info("Epic Connect session of Epic service account {} is ready", accountId);
            schedule("the stale games of " + accountId, () -> dropStale(from), timing.staleGrace());
            resync(from, true);
        }

        /** Lists the bot's friends and publishes them; {@code initial} for the listing that follows a connection. */
        private void resync(Link from, boolean initial) {
            synchronized (resyncLock) {
                if (!from.live()) {
                    return;
                }
                EpicFriendsClient.Summary summary;
                try {
                    summary = debug.batch(accountId, FRIENDS_OPERATION, BatchDebugFilter::epicFriends,
                            () -> friends.summary(from.token.value(), from.botAccountId));
                } catch (EpicAuthException e) {
                    accessRejected(from);
                    return;
                } catch (RuntimeException e) {
                    log.warn("Could not list the friends of Epic service account {}: {}", accountId, e.toString());
                    if (initial) {
                        settle(from);
                    }
                    return;
                }
                if (initial) {
                    settle(from);
                }
                if (from.live()) {
                    execute(handoff, "a friends snapshot of " + accountId,
                            () -> events.publishEvent(new EpicFriendEventReceived(accountId, summary)));
                }
            }
        }

        private void updateGame(Link from, JsonNode payload) {
            String epicAccountId = EpicPresence.accountId(payload);
            if (epicAccountId.isEmpty()) {
                return;
            }
            Optional<EpicGame> game = EpicPresence.currentGame(payload);
            synchronized (this) {
                if (link != from || stopped || !current()) {
                    return;
                }
                EpicGame kept = stale.remove(epicAccountId);
                if (game.isPresent()) {
                    reported.put(epicAccountId, game.get());
                    games.put(epicAccountId, game.get());
                } else {
                    EpicGame previous = reported.remove(epicAccountId);
                    if (previous != null) {
                        games.remove(epicAccountId, previous);
                    }
                    if (kept != null) {
                        games.remove(epicAccountId, kept);
                    }
                }
            }
        }

        /** Called with this session's monitor held. */
        private void forgetGames() {
            reported.forEach(games::remove);
            reported.clear();
            forgetStale();
        }

        /** Called with this session's monitor held. */
        private void forgetStale() {
            stale.forEach(games::remove);
            stale.clear();
        }

        /** The grace after {@code from} registered is over: what it has not reported again is no longer known. */
        private synchronized void dropStale(Link from) {
            if (link == from && !stopped) {
                forgetStale();
            }
        }

        /** One connection and its callbacks: those of a connection the session left are ignored. */
        private final class Link implements EpicConnectListener {

            private final EpicConnectClient client;
            private final AccessToken token;
            private final String botAccountId;
            /** The presence registration succeeded; guarded by the session. */
            private boolean registered;
            private ScheduledFuture<?> renewal;
            private ScheduledFuture<?> registerTimeout;

            Link(EpicConnectClient client, AccessToken token, String botAccountId) {
                this.client = client;
                this.token = token;
                this.botAccountId = botAccountId;
            }

            private boolean live() {
                synchronized (Session.this) {
                    return link == this && !stopped;
                }
            }

            /** Plans the replacement before the token expires, and a deadline for the registration. */
            void startTimers() {
                Duration untilRenewal = Duration.between(clock.instant(), token.expiresAt().minus(RENEW_BEFORE_EXPIRY));
                if (untilRenewal.compareTo(timing.renewalFloor()) < 0) {
                    log.warn("The access token of Epic service account {} expires sooner than expected ({}): "
                            + "renewing its connection in {}", accountId, token.expiresAt(), timing.renewalFloor());
                    // Never a tight loop of refreshes, whatever the token's lifetime
                    untilRenewal = timing.renewalFloor();
                }
                ScheduledFuture<?> renew = schedule("the renewal of " + accountId, () -> {
                    log.info("Renewing the Epic Connect connection of service account {} before its access token "
                            + "expires", accountId);
                    reconnect(this, true, true);
                }, untilRenewal);
                ScheduledFuture<?> timeout = schedule("the registration deadline of " + accountId, () -> {
                    boolean waiting;
                    synchronized (Session.this) {
                        waiting = link == this && !stopped && !registered;
                    }
                    if (waiting) {
                        log.warn("Epic Connect did not confirm the connection of service account {} in time: "
                                + "reconnecting", accountId);
                        reconnect(this, false);
                    }
                }, REGISTER_TIMEOUT);
                synchronized (Session.this) {
                    renewal = renew;
                    registerTimeout = timeout;
                    if (link != this) {
                        cancelTimers();
                    }
                }
            }

            /** Called with the session's monitor held. */
            private void cancelTimers() {
                if (renewal != null) {
                    renewal.cancel(false);
                }
                if (registerTimeout != null) {
                    registerTimeout.cancel(false);
                }
            }

            /** Closes the connection. Never called with the session's monitor held: close() waits for callbacks. */
            void end() {
                synchronized (Session.this) {
                    cancelTimers();
                }
                client.close();
            }

            @Override
            public void onConnected(String connectionId) {
                if (live()) {
                    execute(starter, "the presence registration of " + accountId, () -> register(this, connectionId));
                }
            }

            @Override
            public void onMessage(EpicConnectMessage message) {
                if (!live()) {
                    return;
                }
                String type = message.type();
                record(message);
                if (PRESENCE_UPDATE.equals(type)) {
                    updateGame(this, message.payload());
                } else if (type.startsWith(FRIENDS_PREFIX)) {
                    // The payloads are not known yet (spec §11.1): logged, and only used to list the friends again
                    log.debug("Epic service account {} got {}: {}", accountId, type, message.payload());
                    execute(starter, "a friends resync of " + accountId, () -> resync(this, false));
                } else {
                    log.debug("Ignoring an Epic Connect {} message for service account {}", type, accountId);
                }
            }

            /** Records the message for the members in debug mode it names. */
            private void record(EpicConnectMessage message) {
                if (!debug.watches(accountId)) {
                    return;
                }
                String body = JsonNodeFactory.instance.objectNode().put("type", message.type())
                        .set("payload", message.payload()).toString();
                debug.inbound(accountId, body::contains, "WS", () -> body, () -> "connect." + message.type());
            }

            @Override
            public void onClosed(Throwable cause) {
                synchronized (Session.this) {
                    if (link != this || stopped) {
                        return;
                    }
                    link = null;
                    cancelTimers();
                    // The next connection pushes the whole presence again
                    forgetGames();
                }
                log.warn("Epic Connect session of Epic service account {} dropped, reconnecting: {}", accountId,
                        cause == null ? "closed" : cause.toString());
                registry.counter(DROPPED_COUNTER, "account", accountId.toString()).increment();
                retryLater();
            }
        }
    }
}
