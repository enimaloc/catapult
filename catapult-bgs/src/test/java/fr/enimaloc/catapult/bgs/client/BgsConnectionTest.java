package fr.enimaloc.catapult.bgs.client;

import bgs.protocol.connection.v1.ConnectionServiceProto.DisconnectNotification;
import fr.enimaloc.catapult.bgs.client.BgsConnection.State;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static fr.enimaloc.catapult.bgs.proto.BgsServices.CONNECTION_FORCE_DISCONNECT;
import static fr.enimaloc.catapult.bgs.proto.BgsServices.CONNECTION_KEEP_ALIVE;
import static org.assertj.core.api.Assertions.assertThat;

class BgsConnectionTest {

    private final ClientHarness h = new ClientHarness();
    private final ManualScheduler scheduler = new ManualScheduler();
    private final List<FakeSession> sessions = new ArrayList<>();
    private final BgsConnection connection = new BgsConnection(h.client, () -> {
        h.session = new FakeSession();
        sessions.add(h.session);
        return h.session;
    }, scheduler, Duration.ofSeconds(5), Duration.ofSeconds(120));

    private void readySession() {
        h.connect().logon().accountState().select();
    }

    private long mints() {
        return sessions.stream().flatMap(s -> s.timeline.stream()).filter("mint"::equals).count();
    }

    @Test
    void ioErrorReconnectsWithExponentialBackoffCappedAt120s() {
        connection.start();
        assertThat(connection.state()).isEqualTo(State.CONNECTING);
        long[] delays = {5, 10, 20, 40, 80, 120, 120};
        int expected = 1;
        for (long delay : delays) {
            h.session.drop(new IOException("reset"));
            assertThat(connection.state()).isEqualTo(State.BACKOFF);
            scheduler.advance(delay * 1000 - 1);
            assertThat(sessions).hasSize(expected);
            scheduler.advance(1);
            assertThat(sessions).hasSize(++expected);
            assertThat(connection.state()).isEqualTo(State.CONNECTING);
        }
        // reaching READY resets the backoff, and every reconnection mints again
        readySession();
        assertThat(connection.state()).isEqualTo(State.READY);
        assertThat(mints()).isEqualTo(1);
        h.session.drop(new IOException("reset"));
        scheduler.advance(4_999);
        assertThat(sessions).hasSize(expected);
        scheduler.advance(1);
        assertThat(sessions).hasSize(expected + 1);
        readySession();
        assertThat(mints()).isEqualTo(2);
    }

    @Test
    void sessionReplacedStopsWithoutReconnecting() {
        connection.start();
        readySession();
        h.session.push(CONNECTION_FORCE_DISCONNECT, DisconnectNotification.newBuilder().setErrorCode(60).build());

        assertThat(connection.state()).isEqualTo(State.STOPPED_REPLACED);
        scheduler.advance(10 * 60_000);
        assertThat(sessions).hasSize(1);
        assertThat(scheduler.pending()).isZero();
    }

    @Test
    void otherForceDisconnectBacksOff() {
        connection.start();
        readySession();
        h.session.push(CONNECTION_FORCE_DISCONNECT, DisconnectNotification.newBuilder().setErrorCode(3).build());

        assertThat(connection.state()).isEqualTo(State.BACKOFF);
        scheduler.advance(5_000);
        assertThat(sessions).hasSize(2);
    }

    @Test
    void codeRequiredStopsWithoutReconnecting() {
        h.mintFailure = new TicketException("2FA code required", true);
        connection.start();
        h.connect();

        assertThat(connection.state()).isEqualTo(State.STOPPED_CODE_REQUIRED);
        scheduler.advance(10 * 60_000);
        assertThat(sessions).hasSize(1);
        assertThat(scheduler.pending()).isZero();
    }

    @Test
    void otherMintFailureBacksOff() {
        h.mintFailure = new TicketException("login failed", false);
        connection.start();
        h.connect();

        assertThat(connection.state()).isEqualTo(State.BACKOFF);
        scheduler.advance(5_000);
        assertThat(sessions).hasSize(2);
    }

    @Test
    void keepAliveEvery30sWhileReady() {
        connection.start();
        scheduler.advance(60_000);
        assertThat(h.session.sentTo(CONNECTION_KEEP_ALIVE.serviceHash(), CONNECTION_KEEP_ALIVE.methodId())).isEmpty();

        readySession();
        FakeSession live = h.session;
        scheduler.advance(29_999);
        assertThat(live.sentTo(CONNECTION_KEEP_ALIVE.serviceHash(), CONNECTION_KEEP_ALIVE.methodId())).isEmpty();
        scheduler.advance(1);
        assertThat(live.sentTo(CONNECTION_KEEP_ALIVE.serviceHash(), CONNECTION_KEEP_ALIVE.methodId())).hasSize(1);
        scheduler.advance(30_000);
        assertThat(live.sentTo(CONNECTION_KEEP_ALIVE.serviceHash(), CONNECTION_KEEP_ALIVE.methodId())).hasSize(2);

        live.drop(new IOException("reset"));
        scheduler.advance(4_000);
        assertThat(live.sentTo(CONNECTION_KEEP_ALIVE.serviceHash(), CONNECTION_KEEP_ALIVE.methodId())).hasSize(2);
    }

    @Test
    void stopClosesTheSessionAndCancelsRetries() {
        connection.start();
        readySession();
        connection.stop();

        assertThat(h.session.closed).isTrue();
        assertThat(connection.state()).isEqualTo(State.STOPPED);
        assertThat(scheduler.pending()).isZero();
        scheduler.advance(10 * 60_000);
        assertThat(sessions).hasSize(1);
    }

    @Test
    void stopWhileInBackoffCancelsTheRetry() {
        connection.start();
        h.session.drop(new IOException("reset"));
        assertThat(connection.state()).isEqualTo(State.BACKOFF);
        connection.stop();

        assertThat(connection.state()).isEqualTo(State.STOPPED);
        assertThat(scheduler.pending()).isZero();
        scheduler.advance(10 * 60_000);
        assertThat(sessions).hasSize(1);
    }
}
