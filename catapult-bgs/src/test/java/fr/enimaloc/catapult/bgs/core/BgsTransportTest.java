package fr.enimaloc.catapult.bgs.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BgsTransportTest {

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void aServerThatNeverAnswersTheHandshakeTimesOut() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread acceptor = Thread.ofVirtual().start(() -> {
                // accepts and keeps the connection open without a byte back
                try (Socket ignored = server.accept()) {
                    Thread.sleep(Duration.ofSeconds(20));
                } catch (IOException | InterruptedException e) {
                    // the test is over
                }
            });
            long start = System.nanoTime();

            assertThatThrownBy(() -> BgsTransport.tls(InetAddress.getLoopbackAddress().getHostAddress(),
                    server.getLocalPort(), Duration.ofMillis(500)))
                    .isInstanceOf(SocketTimeoutException.class);

            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
            acceptor.interrupt();
        }
    }
}
