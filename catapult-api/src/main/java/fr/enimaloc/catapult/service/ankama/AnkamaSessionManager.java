package fr.enimaloc.catapult.service.ankama;

import fr.enimaloc.catapult.domain.ankama.AnkamaServiceAccount;
import fr.enimaloc.catapult.domain.ankama.AnkamaServiceAccount.AuthState;
import fr.enimaloc.catapult.repository.ankama.AnkamaServiceAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.ankama.chat.AnkamaChatAuthException;
import fr.enimaloc.catapult.service.ankama.chat.AnkamaChatClient;
import fr.enimaloc.catapult.service.ankama.chat.ChatFrames;
import fr.enimaloc.catapult.service.ankama.chat.ChatListener;
import fr.enimaloc.catapult.service.ankama.chat.ChatMessages;
import fr.enimaloc.catapult.service.ankama.chat.ChatMessages.EndpointProperties;
import fr.enimaloc.catapult.service.ankama.haapi.AnkamaAuthException;
import fr.enimaloc.catapult.service.ankama.haapi.AnkamaHaapiClient;
import fr.enimaloc.catapult.service.ankama.haapi.AnkamaHaapiClient.Certificate;
import fr.enimaloc.catapult.service.ankama.haapi.AnkamaShieldException;
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

import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
 * Keeps one launcher chat session per enabled and {@code READY} Ankama service account (spec §4.3), the game each
 * friend of the bots is playing (spec §3), and the bots' HAAPI keys alive (spec §4.1).
 *
 * <p>Sessions open only when {@code ankama.sessions.enabled=true} and this site is the active one, like the
 * Battle.net ones: a standby site, a dev environment or a restored dump must never use the bots, nor rotate their
 * refresh tokens. They close when the site goes standby and reopen when it becomes active again.
 *
 * <p>Each connection asks for a fresh chat token. A refused token is retried once after a key refresh; a refused
 * refresh asks the admin to log in again ({@code REAUTH_REQUIRED}), a missing or refused Shield certificate asks for
 * a new security code ({@code CODE_REQUIRED}). Either way the session stops until the admin acts.
 *
 * <p>Friend and invite events are handed to a single thread, which keeps the order in which the chat reported them.
 */
@Slf4j
@Service
@ConditionalOnBooleanProperty("ankama.enabled")
public class AnkamaSessionManager implements SmartLifecycle {

    /** {@link #sessionState} of an account that has no session. */
    public static final String DISABLED = "DISABLED";

    /** States of an open session, as {@link #sessionState} names them. */
    enum State { CONNECTING, READY, BACKOFF }

    static final int CHAT_PORT = 6337;
    static final Duration HEARTBEAT = Duration.ofSeconds(20);
    static final Duration MIN_BACKOFF = Duration.ofSeconds(5);
    static final Duration MAX_BACKOFF = Duration.ofSeconds(120);
    static final Duration REFRESH_AFTER = Duration.ofHours(24);
    static final Duration REFRESH_PERIOD = Duration.ofHours(1);
    static final Duration FIRST_REFRESH_DELAY = Duration.ofMinutes(1);
    /** Consecutive refused chat logins before the key is refreshed, then before the admin is asked. */
    static final int MAX_CHAT_AUTH_FAILURES = 3;
    static final String ADMIN_ACTION_COUNTER = "catapult.ankama.session.admin_action_required";
    static final String DROPPED_COUNTER = "catapult.ankama.session.dropped";

    private final AnkamaServiceAccountRepository repository;
    private final AnkamaHaapiClient haapi;
    private final TokenEncryptionService encryption;
    private final SiteRole siteRole;
    private final ApplicationEventPublisher events;
    private final MeterRegistry registry;
    private final TransactionOperations transactions;
    private final boolean sessionsEnabled;
    private final Supplier<AnkamaChatClient> clients;
    private final ScheduledExecutorService scheduler;
    private final Executor starter;
    private final Executor handoff;
    private final Clock clock;
    private final Duration minBackoff;
    private final List<ExecutorService> owned;

    private final Object lock = new Object();
    /** Serializes the key refreshes: two refreshes with the same refresh token would revoke each other. */
    private final Object refreshLock = new Object();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    /** Ankama application id by Ankama user id, for the friends of every open session. */
    private final Map<Long, Integer> games = new ConcurrentHashMap<>();
    private volatile boolean running;
    private ScheduledFuture<?> refreshTask;

    @Autowired
    public AnkamaSessionManager(AnkamaServiceAccountRepository repository,
                                AnkamaHaapiClient haapi,
                                TokenEncryptionService encryption,
                                SiteRole siteRole,
                                ApplicationEventPublisher events,
                                MeterRegistry registry,
                                TransactionOperations transactions,
                                @Value("${ankama.sessions.enabled:false}") boolean sessionsEnabled,
                                @Value("${ankama.chat-host:zaap-chat.ankama.com}") String chatHost) {
        this(repository, haapi, encryption, siteRole, events, registry, transactions, sessionsEnabled, chatHost,
                Executors.newScheduledThreadPool(2, Thread.ofPlatform().name("ankama-chat-timers-", 0).daemon()
                        .factory()));
    }

    private AnkamaSessionManager(AnkamaServiceAccountRepository repository,
                                 AnkamaHaapiClient haapi,
                                 TokenEncryptionService encryption,
                                 SiteRole siteRole,
                                 ApplicationEventPublisher events,
                                 MeterRegistry registry,
                                 TransactionOperations transactions,
                                 boolean sessionsEnabled,
                                 String chatHost,
                                 ScheduledExecutorService scheduler) {
        this(repository, haapi, encryption, siteRole, events, registry, transactions, sessionsEnabled,
                () -> new AnkamaChatClient(SSLSocketFactory.getDefault(), chatHost, CHAT_PORT, HEARTBEAT, scheduler),
                scheduler,
                // Connecting blocks on HTTP, TCP and TLS: never on the timers
                Executors.newVirtualThreadPerTaskExecutor(),
                Executors.newSingleThreadExecutor(Thread.ofPlatform().name("ankama-sessions").daemon().factory()),
                Clock.systemUTC(),
                MIN_BACKOFF);
    }

    /**
     * @param clients    builds the single-use chat client of each connection
     * @param starter    runs each connection attempt (HTTP and TLS, blocking)
     * @param handoff    runs, in order, the friend events publication
     * @param minBackoff first delay before a reconnection, doubled up to {@link #MAX_BACKOFF}
     */
    AnkamaSessionManager(AnkamaServiceAccountRepository repository,
                         AnkamaHaapiClient haapi,
                         TokenEncryptionService encryption,
                         SiteRole siteRole,
                         ApplicationEventPublisher events,
                         MeterRegistry registry,
                         TransactionOperations transactions,
                         boolean sessionsEnabled,
                         Supplier<AnkamaChatClient> clients,
                         ScheduledExecutorService scheduler,
                         Executor starter,
                         Executor handoff,
                         Clock clock,
                         Duration minBackoff) {
        this.repository = repository;
        this.haapi = haapi;
        this.encryption = encryption;
        this.siteRole = siteRole;
        this.events = events;
        this.registry = registry;
        this.transactions = transactions;
        this.sessionsEnabled = sessionsEnabled;
        this.clients = clients;
        this.scheduler = scheduler;
        this.starter = starter;
        this.handoff = handoff;
        this.clock = clock;
        this.minBackoff = minBackoff;
        this.owned = Stream.of(scheduler, starter, handoff)
                .filter(ExecutorService.class::isInstance).map(ExecutorService.class::cast).toList();
    }

    // --- lifecycle ---

    @Override
    public void start() {
        synchronized (lock) {
            running = true;
            if (!sessionsEnabled) {
                log.info("Ankama chat sessions are disabled (ankama.sessions.enabled=false)");
                return;
            }
            if (refreshTask == null) {
                try {
                    refreshTask = scheduler.scheduleWithFixedDelay(this::refreshDueTokens,
                            FIRST_REFRESH_DELAY.toMillis(), REFRESH_PERIOD.toMillis(), TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException e) {
                    log.warn("Could not schedule the Ankama key refreshes: the executor is shut down");
                }
            }
            openEligible();
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            running = false;
            if (refreshTask != null) {
                refreshTask.cancel(false);
                refreshTask = null;
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
                    log.info("Site went standby: closing the {} Ankama chat session(s)", sessions.size());
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
            repository.findById(accountId).filter(AnkamaSessionManager::eligible).ifPresent(this::open);
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

    /** The application id of the Ankama game the user plays, as reported to the bot they are friends with. */
    public OptionalInt currentGame(long ankamaUserId) {
        Integer game = games.get(ankamaUserId);
        return game == null ? OptionalInt.empty() : OptionalInt.of(game);
    }

    /**
     * Sends {@code payload} on the account's chat connection. Fails at once, with an {@link IllegalStateException},
     * when the account has no open connection: requests are never queued.
     */
    public CompletableFuture<ChatFrames.Response> request(UUID accountId, ChatFrames.Payload payload) {
        Session session = sessions.get(accountId);
        AnkamaChatClient client = session == null ? null : session.openClient();
        if (client == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("No open chat session for this Ankama service account"));
        }
        return client.request(payload);
    }

    /**
     * Refreshes the key of every {@code READY} bot not refreshed for {@link #REFRESH_AFTER}; runs every
     * {@link #REFRESH_PERIOD}, on the active site only. A refused refresh asks the admin to log in again.
     */
    void refreshDueTokens() {
        if (!canOpen()) {
            return;
        }
        List<AnkamaServiceAccount> ready;
        try {
            ready = repository.findByAuthState(AuthState.READY);
        } catch (RuntimeException e) {
            log.warn("Could not list the Ankama service accounts to refresh their keys: {}", e.toString());
            return;
        }
        Instant due = clock.instant().minus(REFRESH_AFTER);
        for (AnkamaServiceAccount account : ready) {
            if (!account.getLastRefreshAt().isBefore(due)) {
                continue;
            }
            try {
                refresh(account.getId());
            } catch (AnkamaAuthException e) {
                log.warn("Ankama refused to refresh the key of service account {}: it needs a new login",
                        account.getId());
                requireAdmin(account.getId(), AuthState.REAUTH_REQUIRED);
            } catch (RuntimeException e) {
                log.warn("Could not refresh the key of Ankama service account {}, retrying in {}: {}",
                        account.getId(), REFRESH_PERIOD, e.toString());
            }
        }
    }

    // --- internals ---

    private static boolean eligible(AnkamaServiceAccount account) {
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
            log.warn("Could not list the Ankama service accounts to open their sessions: {}", e.toString());
        }
    }

    /** Called with the lock held. */
    private void open(AnkamaServiceAccount account) {
        if (sessions.containsKey(account.getId())) {
            return;
        }
        Session session = new Session(account.getId());
        sessions.put(account.getId(), session);
        log.info("Opening the chat session of Ankama service account {}", account.getId());
        session.launch();
    }

    /** Called with the lock held. */
    private void close(UUID accountId) {
        Session session = sessions.remove(accountId);
        if (session != null) {
            log.info("Closing the chat session of Ankama service account {}", accountId);
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

    /**
     * Rotates the account's refresh token. The HTTP call runs outside any transaction; the account is read again to
     * store the result.
     *
     * @throws AnkamaAuthException when Ankama refuses the key or the refresh token
     */
    private void refresh(UUID accountId) {
        synchronized (refreshLock) {
            AnkamaServiceAccount account = repository.findById(accountId).orElse(null);
            if (account == null) {
                return;
            }
            String refreshToken = haapi.refresh(encryption.decrypt(account.getApiKeyEnc()),
                    encryption.decrypt(account.getRefreshTokenEnc()));
            String refreshTokenEnc = encryption.encrypt(refreshToken);
            Instant now = clock.instant();
            transactions.executeWithoutResult(status -> repository.findById(accountId).ifPresent(stored -> {
                stored.setRefreshTokenEnc(refreshTokenEnc);
                stored.setLastRefreshAt(now);
                repository.save(stored);
            }));
            log.info("Refreshed the key of Ankama service account {}", accountId);
        }
    }

    private void setAuthState(UUID accountId, AuthState state) {
        try {
            transactions.executeWithoutResult(status -> repository.findById(accountId).ifPresent(account -> {
                account.setAuthState(state);
                repository.save(account);
            }));
        } catch (RuntimeException e) {
            log.warn("Could not set Ankama service account {} to {}: {}", accountId, state, e.toString());
        }
    }

    /** Marks the account, counts it and stops whatever session it has until the admin acts. */
    private void requireAdmin(UUID accountId, AuthState state) {
        setAuthState(accountId, state);
        registry.counter(ADMIN_ACTION_COUNTER, "account", accountId.toString()).increment();
        synchronized (lock) {
            close(accountId);
        }
    }

    /** One account's successive connections and the presence they reported. */
    private final class Session {

        private final UUID accountId;
        /** Endpoints and presence by Ankama user id, as this session's connections reported them. */
        private final Map<Long, List<EndpointProperties>> endpoints = new ConcurrentHashMap<>();
        private final Map<Long, Integer> presence = new ConcurrentHashMap<>();
        private volatile State state = State.CONNECTING;
        private volatile boolean stopped;
        /** The connection in progress or open; null between two. Guarded by this. */
        private Link link;
        private ScheduledFuture<?> retry;
        private Duration backoff = minBackoff;
        /** Only touched by the attempts, which never overlap. */
        private int chatAuthFailures;
        private boolean refreshedForChat;

        Session(UUID accountId) {
            this.accountId = accountId;
        }

        void launch() {
            try {
                starter.execute(this::attempt);
            } catch (RejectedExecutionException e) {
                log.warn("Could not start the chat session of {}: the executor is shut down", accountId);
            }
        }

        void stop() {
            AnkamaChatClient open;
            synchronized (this) {
                stopped = true;
                if (retry != null) {
                    retry.cancel(false);
                }
                // A connection still in progress is closed by its attempt once connect() returns
                open = link != null && link.open ? link.client : null;
                link = null;
            }
            forgetGames();
            if (open != null) {
                open.close();
            }
        }

        synchronized AnkamaChatClient openClient() {
            return link != null && link.open ? link.client : null;
        }

        private boolean current() {
            return sessions.get(accountId) == this;
        }

        /**
         * Whether this session still owns the account: neither stopped nor replaced. An attempt blocked in an HTTP
         * call may outlive its session, and must then neither refresh the key nor touch the account.
         */
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

        /**
         * Marks the account, counts it and ends this session until the admin acts; does nothing when the session no
         * longer owns the account (a newer one may run on a fresh login).
         *
         * @return whether the account was marked
         */
        private boolean stopForAdmin(AuthState state) {
            synchronized (lock) {
                if (!owned()) {
                    return false;
                }
                setAuthState(accountId, state);
                registry.counter(ADMIN_ACTION_COUNTER, "account", accountId.toString()).increment();
                close(accountId);
                return true;
            }
        }

        private void attempt() {
            if (stopped) {
                return;
            }
            state = State.CONNECTING;
            AnkamaServiceAccount account;
            try {
                account = repository.findById(accountId).orElse(null);
            } catch (RuntimeException e) {
                log.warn("Could not read Ankama service account {}: {}", accountId, e.toString());
                retryLater();
                return;
            }
            if (account == null || !eligible(account)) {
                log.info("Ankama service account {} is no longer enabled and ready: closing its session", accountId);
                end();
                return;
            }
            String key = null;
            String token;
            try {
                key = encryption.decrypt(account.getApiKeyEnc());
                token = chatToken(account, key);
            } catch (AnkamaShieldException e) {
                if (stopForAdmin(AuthState.CODE_REQUIRED)) {
                    log.warn("Ankama wants a Shield code for service account {}: sending one, the session waits "
                            + "for the admin", accountId);
                    sendShieldCode(key);
                }
                return;
            } catch (AnkamaAuthException e) {
                if (stopForAdmin(AuthState.REAUTH_REQUIRED)) {
                    log.warn("Ankama refused the key of service account {} even after a refresh: it needs a new "
                            + "login", accountId);
                }
                return;
            } catch (RuntimeException e) {
                // The class name only: a transport error message carries the URI with the certificate proof
                log.warn("Could not get a chat token for Ankama service account {}: {}", accountId,
                        e.getClass().getName());
                retryLater();
                return;
            }
            AnkamaChatClient client = clients.get();
            Link candidate = new Link(client);
            synchronized (this) {
                if (stopped) {
                    return;
                }
                link = candidate;
            }
            try {
                client.connect(token, account.getDeviceId(), candidate);
            } catch (AnkamaChatAuthException e) {
                forget(candidate);
                chatAuthRefused();
                return;
            } catch (IOException | RuntimeException e) {
                forget(candidate);
                log.warn("Could not connect Ankama service account {} to the chat: {}", accountId, e.toString());
                retryLater();
                return;
            }
            synchronized (this) {
                if (stopped) {
                    client.close();
                    return;
                }
                if (link != candidate) {
                    // Dropped before connect() returned: onClosed already planned the next attempt
                    return;
                }
                candidate.open = true;
                state = State.READY;
                backoff = minBackoff;
            }
            chatAuthFailures = 0;
            refreshedForChat = false;
            log.info("Chat session of Ankama service account {} is ready", accountId);
            resync(client, account.getAnkamaAccountId());
        }

        private String chatToken(AnkamaServiceAccount account, String key) {
            Certificate certificate = account.getCertificateId() == null || account.getCertificateEnc() == null
                    ? null
                    : new Certificate(account.getCertificateId(), encryption.decrypt(account.getCertificateEnc()));
            try {
                return haapi.chatToken(key, certificate, account.getHm1());
            } catch (AnkamaAuthException e) {
                if (!owned()) {
                    throw e;
                }
                log.info("Ankama refused the chat token of service account {}: refreshing its key once", accountId);
                refresh(accountId);
                return haapi.chatToken(key, certificate, account.getHm1());
            }
        }

        private void sendShieldCode(String key) {
            try {
                haapi.sendShieldCode(key);
            } catch (RuntimeException e) {
                log.warn("Could not send a Shield code for Ankama service account {}: {}", accountId, e.toString());
            }
        }

        /** {@link #MAX_CHAT_AUTH_FAILURES} refusals in a row: refresh the key once, then ask the admin. */
        private void chatAuthRefused() {
            if (!owned()) {
                return;
            }
            if (++chatAuthFailures < MAX_CHAT_AUTH_FAILURES) {
                log.warn("The chat refused the token of Ankama service account {} ({} in a row)", accountId,
                        chatAuthFailures);
                retryLater();
                return;
            }
            chatAuthFailures = 0;
            if (refreshedForChat) {
                if (stopForAdmin(AuthState.REAUTH_REQUIRED)) {
                    log.warn("The chat still refuses Ankama service account {} after a key refresh: it needs a new "
                            + "login", accountId);
                }
                return;
            }
            refreshedForChat = true;
            if (!owned()) {
                return;
            }
            try {
                refresh(accountId);
            } catch (AnkamaAuthException e) {
                if (stopForAdmin(AuthState.REAUTH_REQUIRED)) {
                    log.warn("Ankama refused to refresh the key of service account {}: it needs a new login",
                            accountId);
                }
                return;
            } catch (RuntimeException e) {
                log.warn("Could not refresh the key of Ankama service account {}: {}", accountId, e.toString());
            }
            retryLater();
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
                state = State.BACKOFF;
                Duration delay = backoff;
                Duration doubled = backoff.multipliedBy(2);
                backoff = doubled.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : doubled;
                try {
                    retry = scheduler.schedule(this::launch, delay.toMillis(), TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException e) {
                    log.debug("No reconnection for {}: the executor is shut down", accountId);
                }
            }
        }

        /** Replays the pending invites, then the friends, so that the links catch up on what they missed. */
        private void resync(AnkamaChatClient client, long botUserId) {
            CompletableFuture<ChatFrames.Response> invites =
                    client.request(ChatMessages.listInvites(botUserId).toPayload());
            CompletableFuture<ChatFrames.Response> friends =
                    client.request(ChatMessages.listFriends(botUserId).toPayload());
            invites.thenCombine(friends, (i, f) -> {
                List<ChatMessages.Event> replay = new ArrayList<>();
                try {
                    ChatMessages.inviteList(payloadOf(i))
                            .forEach(invite -> replay.add(new ChatMessages.InviteCreated(invite)));
                    ChatMessages.friendList(payloadOf(f))
                            .forEach(friend -> replay.add(new ChatMessages.FriendCreated(botUserId, friend)));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return replay;
            }).whenComplete((replay, failure) -> {
                if (failure != null) {
                    log.warn("Could not list the friends and invites of Ankama service account {}: {}", accountId,
                            failure.toString());
                } else if (!stopped) {
                    replay.forEach(event -> publish(event, true));
                }
            });
        }

        private static ChatFrames.Payload payloadOf(ChatFrames.Response response) throws IOException {
            if (response.status() != 1 || response.payload() == null) {
                throw new IOException("The chat answered with status " + response.status());
            }
            return response.payload();
        }

        private void publish(ChatMessages.Event event) {
            publish(event, false);
        }

        private void publish(ChatMessages.Event event, boolean replayed) {
            handOff("a friend event of " + accountId,
                    () -> events.publishEvent(new AnkamaFriendEventReceived(accountId, event, replayed)));
        }

        private void updateGame(long userId) {
            if (!current()) {
                return;
            }
            OptionalInt game = AnkamaPresence.currentGame(endpoints.getOrDefault(userId, List.of()),
                    presence.getOrDefault(userId, ChatMessages.PRESENCE_ONLINE));
            if (game.isPresent()) {
                games.put(userId, game.getAsInt());
            } else {
                games.remove(userId);
            }
        }

        private void forgetGames() {
            Set<Long> reported = new HashSet<>(endpoints.keySet());
            reported.addAll(presence.keySet());
            reported.forEach(games::remove);
            endpoints.clear();
            presence.clear();
        }

        /** The callbacks of one connection: those of a connection the session left are ignored. */
        private final class Link implements ChatListener {

            private final AnkamaChatClient client;
            /** connect() returned; guarded by the session. */
            private boolean open;

            Link(AnkamaChatClient client) {
                this.client = client;
            }

            private boolean live() {
                synchronized (Session.this) {
                    return link == this && !stopped;
                }
            }

            @Override
            public void onEvent(ChatMessages.Event event) {
                if (!live()) {
                    return;
                }
                switch (event) {
                    case ChatMessages.EndpointsUpdated e -> {
                        endpoints.put(e.userId(), e.properties());
                        updateGame(e.userId());
                    }
                    case ChatMessages.PresenceUpdated p -> {
                        presence.put(p.userId(), p.presence());
                        updateGame(p.userId());
                    }
                    case ChatMessages.InviteCreated e -> publish(e);
                    case ChatMessages.InviteDeleted e -> publish(e);
                    case ChatMessages.FriendCreated e -> publish(e);
                    case ChatMessages.FriendDeleted e -> publish(e);
                    case ChatMessages.Unknown ignored -> {
                        // not used by Catapult
                    }
                }
            }

            @Override
            public void onClosed(Throwable cause) {
                synchronized (Session.this) {
                    if (link != this || stopped) {
                        return;
                    }
                    link = null;
                }
                // The next connection pushes the whole presence again
                forgetGames();
                log.warn("Chat session of Ankama service account {} dropped, reconnecting: {}", accountId,
                        cause == null ? "closed" : cause.toString());
                registry.counter(DROPPED_COUNTER, "account", accountId.toString()).increment();
                retryLater();
            }
        }
    }
}
