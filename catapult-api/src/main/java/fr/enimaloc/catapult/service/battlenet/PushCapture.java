package fr.enimaloc.catapult.service.battlenet;

import com.google.protobuf.ByteString;
import fr.enimaloc.catapult.bgs.core.WireExchange;
import fr.enimaloc.catapult.bgs.core.WireTap;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the calls the server sends a bot during the few seconds after an admin's free BGS call, so the admin sees
 * what the call caused (a presence change arrives as a push, not in the reply). One per session; armed by one call at
 * a time, which makes it the per-bot "a call is running" lock too. The keep-alive traffic is left out.
 *
 * <p>{@link #onExchange} runs on the session loop, {@link #arm} and {@link #release} on an HTTP thread.
 */
final class PushCapture implements WireTap {

    private static final int CONNECTION_SERVICE = 0x65446991;
    private static final int ECHO = 3;
    private static final int KEEP_ALIVE = 5;

    record CapturedPush(Instant at, int serviceHash, int methodId, ByteString body) {
    }

    private final Clock clock;
    private final List<CapturedPush> pushes = new ArrayList<>();
    private Instant deadline;

    PushCapture() {
        this(Clock.systemUTC());
    }

    PushCapture(Clock clock) {
        this.clock = clock;
    }

    /** Starts a window of {@code window} from now; false when another call holds the capture. */
    synchronized boolean arm(Duration window) {
        if (deadline != null) {
            return false;
        }
        pushes.clear();
        deadline = clock.instant().plus(window);
        return true;
    }

    /** The pushes of the window; disarms, so the next call can arm. */
    synchronized List<CapturedPush> release() {
        List<CapturedPush> kept = List.copyOf(pushes);
        pushes.clear();
        deadline = null;
        return kept;
    }

    @Override
    public synchronized boolean wants() {
        return deadline != null && clock.instant().isBefore(deadline);
    }

    @Override
    public synchronized void onExchange(WireExchange exchange) {
        if (exchange.initiatedLocally() || !wants()) {
            return;
        }
        int hash = exchange.requestHeader().getServiceHash();
        int method = exchange.requestHeader().getMethodId();
        if (hash == CONNECTION_SERVICE && (method == KEEP_ALIVE || method == ECHO)) {
            return;
        }
        pushes.add(new CapturedPush(clock.instant(), hash, method, exchange.requestBody()));
    }
}
