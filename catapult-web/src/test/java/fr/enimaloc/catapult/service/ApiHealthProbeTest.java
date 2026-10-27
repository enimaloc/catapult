package fr.enimaloc.catapult.service;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import fr.enimaloc.catapult.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;

class ApiHealthProbeTest {

    private MockWebServer server;
    private MutableClock clock;
    private ApiHealthProbe probe;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        clock = new MutableClock(Instant.parse("2026-10-27T10:00:00Z"));
        probe = new ApiHealthProbe(server.url("/").toString(), clock);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void upWhenTheApiAnswers200() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"status\":\"UP\"}"));

        assertThat(probe.up()).isTrue();
        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/api/internal/health");
        assertThat(request.getHeader("Authorization")).isNull();
    }

    @Test
    void downOnErrorStatus() {
        server.enqueue(new MockResponse().setResponseCode(503));

        assertThat(probe.up()).isFalse();
    }

    @Test
    void downWhenTheApiIsUnreachable() throws IOException {
        server.shutdown();

        assertThat(probe.up()).isFalse();
    }

    @Test
    void downOnTimeout() {
        server.enqueue(new MockResponse().setHeadersDelay(3, TimeUnit.SECONDS));

        long start = System.nanoTime();
        boolean up = probe.up();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(up).isFalse();
        assertThat(elapsedMillis).isLessThan(2500);
    }

    @Test
    void oneProbePerFiveSecondsUnderConcurrency() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"status\":\"UP\"}").setHeadersDelay(300, TimeUnit.MILLISECONDS));
        int callers = 20;
        CountDownLatch go = new CountDownLatch(1);
        List<Boolean> answers = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            Thread thread = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                answers.add(probe.up());
            });
            thread.start();
            threads.add(thread);
        }
        go.countDown();
        for (Thread thread : threads) thread.join();

        assertThat(answers).hasSize(callers).containsOnly(true);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void probesAgainOnceTheResultIsFiveSecondsOld() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setBody("{\"status\":\"UP\"}"));

        assertThat(probe.up()).isFalse();
        clock.advance(ApiHealthProbe.TTL.minusMillis(1));
        assertThat(probe.up()).isFalse();
        assertThat(server.getRequestCount()).isEqualTo(1);

        clock.advance(java.time.Duration.ofMillis(1));
        assertThat(probe.up()).isTrue();
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void downWhenInterrupted() {
        server.enqueue(new MockResponse().setHeadersDelay(1, TimeUnit.SECONDS));
        Thread.currentThread().interrupt();

        assertThat(probe.up()).isFalse();
        assertThat(Thread.interrupted()).isTrue();
    }
}
