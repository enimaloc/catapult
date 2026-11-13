package fr.enimaloc.catapult.bgs.client;

import fr.enimaloc.catapult.bgs.client.BgsListener.CloseReason;
import fr.enimaloc.catapult.bgs.core.BgsChannel;

import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Keeps one {@link BgsClient} connected: opens a session through the factory, sends a KeepAlive while it is ready
 * and reconnects with an exponential backoff when it drops. It stops for good when the session is replaced by
 * another login, when the login asks for a 2FA code, or on {@link #stop()}.
 *
 * <p>Reconnection is driven by {@link BgsClient#closed()} and by the failure of the {@link BgsClient#start} future;
 * the ticket is minted again by the client on every attempt.
 */
public final class BgsConnection {

    public enum State { CONNECTING, READY, BACKOFF, STOPPED_REPLACED, STOPPED_CODE_REQUIRED, STOPPED }

    static final Duration KEEP_ALIVE_INTERVAL = Duration.ofSeconds(30);

    private static final System.Logger LOG = System.getLogger(BgsConnection.class.getName());

    private final BgsClient client;
    private final Supplier<BgsChannel> sessionFactory;
    private final ScheduledExecutorService scheduler;
    private final Duration minBackoff;
    private final Duration maxBackoff;
    private final Object lock = new Object();

    private volatile State state = State.STOPPED;
    private boolean started;
    private long attempt;
    private Duration nextBackoff;
    private ScheduledFuture<?> retry;
    private ScheduledFuture<?> keepAlive;

    public BgsConnection(BgsClient client, Supplier<BgsChannel> sessionFactory, ScheduledExecutorService scheduler,
                         Duration minBackoff, Duration maxBackoff) {
        this.client = client;
        this.sessionFactory = sessionFactory;
        this.scheduler = scheduler;
        this.minBackoff = minBackoff;
        this.maxBackoff = maxBackoff;
        this.nextBackoff = minBackoff;
    }

    public State state() {
        return state;
    }

    /** Opens the first session; a no-op once started. */
    public void start() {
        synchronized (lock) {
            if (started) {
                return;
            }
            started = true;
            connect();
        }
    }

    /** Closes the session and cancels any retry; never reconnects afterwards. */
    public void stop() {
        synchronized (lock) {
            attempt++;
            cancelTimers();
            if (!isTerminal()) {
                transition(State.STOPPED);
            }
        }
        client.close();
    }

    private boolean isTerminal() {
        return state == State.STOPPED || state == State.STOPPED_REPLACED || state == State.STOPPED_CODE_REQUIRED;
    }

    /** Opens a session. Called with the lock held. */
    private void connect() {
        long id = ++attempt;
        transition(State.CONNECTING);
        CompletableFuture<Void> ready;
        CompletableFuture<CloseReason> closed;
        try {
            ready = client.start(sessionFactory.get());
            closed = client.closed();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "BGS session could not be opened: " + e);
            backoff();
            return;
        }
        ready.whenComplete((v, error) -> onStarted(id, error));
        closed.thenAccept(reason -> onClosed(id, reason));
    }

    private void onStarted(long id, Throwable error) {
        synchronized (lock) {
            if (id != attempt || state != State.CONNECTING) {
                return;
            }
            if (error == null) {
                nextBackoff = minBackoff;
                transition(State.READY);
                keepAlive = scheduler.scheduleWithFixedDelay(client::keepAlive, KEEP_ALIVE_INTERVAL.toMillis(),
                        KEEP_ALIVE_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
                return;
            }
            Throwable cause = error instanceof CompletionException && error.getCause() != null
                    ? error.getCause() : error;
            if (cause instanceof TicketException te && te.codeRequired()) {
                attempt++;
                transition(State.STOPPED_CODE_REQUIRED);
                client.close();
            }
            // any other failure ends the session, and its close reason drives the retry
        }
    }

    private void onClosed(long id, CloseReason reason) {
        synchronized (lock) {
            if (id != attempt || isTerminal()) {
                return;
            }
            cancelTimers();
            switch (reason) {
                case SESSION_REPLACED -> {
                    LOG.log(Level.ERROR, "BGS session replaced by another login: not reconnecting");
                    transition(State.STOPPED_REPLACED);
                }
                case CODE_REQUIRED -> transition(State.STOPPED_CODE_REQUIRED);
                case LOCAL -> transition(State.STOPPED);
                default -> {
                    LOG.log(Level.WARNING, "BGS session lost (" + reason + ")");
                    backoff();
                }
            }
        }
    }

    /** Schedules the next attempt and doubles the delay up to the cap. Called with the lock held. */
    private void backoff() {
        Duration delay = nextBackoff;
        nextBackoff = nextBackoff.multipliedBy(2).compareTo(maxBackoff) > 0 ? maxBackoff : nextBackoff.multipliedBy(2);
        transition(State.BACKOFF);
        LOG.log(Level.INFO, "BGS reconnecting in " + delay.toSeconds() + " s");
        retry = scheduler.schedule(() -> {
            synchronized (lock) {
                if (state == State.BACKOFF) {
                    connect();
                }
            }
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void cancelTimers() {
        if (retry != null) {
            retry.cancel(false);
            retry = null;
        }
        if (keepAlive != null) {
            keepAlive.cancel(false);
            keepAlive = null;
        }
    }

    private void transition(State next) {
        if (state != next) {
            LOG.log(Level.INFO, "BGS connection " + state + " -> " + next);
            state = next;
        }
    }
}
