package fr.enimaloc.catapult.twitchat;

import fr.enimaloc.catapult.controller.SseStreams;
import fr.enimaloc.catapult.monitoring.InteractionMetrics;
import fr.enimaloc.catapult.service.http.ApiClient;
import jakarta.servlet.http.HttpSession;
import org.springframework.mock.web.MockHttpSession;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TwitchatDispatchHubTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SseStreams sse = SseStreams.direct(new InteractionMetrics(registry));
    private final NotificationFlusher flusher = mock(NotificationFlusher.class);
    /** Runs the flushes on the calling thread, so that they can be asserted on. */
    private final TwitchatDispatchHub hub = new TwitchatDispatchHub(sse, flusher, Runnable::run);

    private static HttpSession sessionNamed(String id) {
        MockHttpSession session = new MockHttpSession(null, id);
        session.setAttribute(ApiClient.SESSION_JWT_KEY, "jwt");
        return session;
    }

    private static HttpSession sessionWithJwt(String jwt) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ApiClient.SESSION_JWT_KEY, jwt);
        return session;
    }

    private double widgetsOpen() {
        return registry.get("catapult.web.sse.streams").tag("stream", "twitchat-widget").gauge().value();
    }

    @Test
    void aClosedPage_noLongerCountsForItsOwner_whoIsForgottenWithItsLastOne() {
        UUID owner = UUID.randomUUID();
        SseEmitter first = hub.subscribe(owner, sessionWithJwt("j1"));
        SseEmitter second = hub.subscribe(owner, sessionWithJwt("j1"));

        sse.close(first);
        assertThat(hub.subscriberCount(owner)).isEqualTo(1);
        sse.close(second);
        assertThat(hub.subscriberCount(owner)).isZero();

        hub.publish(owner, "{\"id\":\"d-1\"}");
        assertThat(hub.subscribe(owner, sessionWithJwt("j1"))).isNotNull();
        assertThat(hub.subscriberCount(owner)).isEqualTo(1);
    }

    @Test
    void aSessionLoggingOut_closesItsOwnPages_only() {
        UUID owner = UUID.randomUUID();
        hub.subscribe(owner, sessionNamed("logged-out"));
        hub.subscribe(owner, sessionNamed("logged-out"));
        hub.subscribe(owner, sessionNamed("still-in"));

        sse.closeSession("logged-out");

        assertThat(hub.subscriberCount(owner)).isEqualTo(1);
    }

    @Test
    void aClosedWidget_freesItsAddressesSlot() {
        UUID owner = UUID.randomUUID();
        SseEmitter widget = hub.subscribeWidget(owner, UUID.randomUUID(), "1.2.3.4", Duration.ofMinutes(10));
        assertThat(widgetsOpen()).isEqualTo(1);

        sse.close(widget);

        assertThat(widgetsOpen()).isZero();
        assertThat(hub.subscriberCount(owner)).isZero();
    }

    @Test
    void publish_reachesOnlyThatUsersStreams() {
        UUID owner = UUID.randomUUID();
        SseEmitter mine = hub.subscribe(owner, sessionWithJwt("j1"));
        SseEmitter other = hub.subscribe(UUID.randomUUID(), sessionWithJwt("j2"));

        hub.publish(owner, "{\"id\":\"d-1\"}");

        assertThat(hub.subscriberCount(owner)).isEqualTo(1);
        assertThat(mine).isNotSameAs(other);
    }

    @Test
    void widgetStreams_closeAfterTheirTimeout_andAreBoundedPerAddress() {
        UUID owner = UUID.randomUUID();

        assertThat(hub.subscribeWidget(owner, UUID.randomUUID(), "1.2.3.4", Duration.ofMinutes(10)).getTimeout()).isEqualTo(600_000L);
        assertThat(hub.subscribe(owner, sessionWithJwt("j1")).getTimeout()).isEqualTo(SseStreams.TIMEOUT.toMillis());
        assertThat(hub.subscriberCount(owner)).isEqualTo(2);
    }

    @Test
    void publish_withoutSubscribers_isANoOp() {
        hub.publish(UUID.randomUUID(), "{}");
    }

    @Test
    void openingAPage_flushesTheOwnersPendingNotifications_withTheSessionsJwt() {
        UUID owner = UUID.randomUUID();
        when(flusher.flushPage("page.jwt")).thenReturn(200);

        hub.subscribe(owner, sessionWithJwt("page.jwt"));

        verify(flusher).flushPage("page.jwt");
        verify(flusher, never()).flushWidget(any());
    }

    @Test
    void openingAWidget_flushesWithItsToken() {
        UUID owner = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        when(flusher.flushWidget(token)).thenReturn(200);

        hub.subscribeWidget(owner, token, "1.2.3.4", Duration.ofMinutes(10));

        verify(flusher).flushWidget(token);
        verify(flusher, never()).flushPage(anyString());
    }

    @Test
    void aFlushThatFails_neverFailsTheStreamOpening() {
        UUID owner = UUID.randomUUID();
        when(flusher.flushPage(anyString())).thenThrow(new IllegalStateException("boom"));

        assertThat(hub.subscribe(owner, sessionWithJwt("j"))).isNotNull();
        assertThat(hub.subscriberCount(owner)).isEqualTo(1);
    }

    @Test
    void theFlush_runsOnTheExecutor_notOnTheOpeningThread() {
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        TwitchatDispatchHub async = new TwitchatDispatchHub(sse, flusher, queued::add);

        async.subscribe(UUID.randomUUID(), sessionWithJwt("j"));

        verifyNoInteractions(flusher);
        queued.forEach(Runnable::run);
        verify(flusher).flushPage("j");
    }

    @Test
    void flush_goesToTheLocallyConnectedOwnerOnly() {
        UUID connected = UUID.randomUUID();
        hub.subscribe(connected, sessionWithJwt("mine"));
        org.mockito.Mockito.clearInvocations(flusher);

        hub.flush(UUID.randomUUID());
        verifyNoInteractions(flusher);

        hub.flush(connected);
        verify(flusher).flushPage("mine");
    }

    @Test
    void flush_ofAWidgetOnlyOwner_usesTheWidgetToken() {
        UUID owner = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        hub.subscribeWidget(owner, token, "1.2.3.4", Duration.ofMinutes(10));
        org.mockito.Mockito.clearInvocations(flusher);

        hub.flush(owner);

        verify(flusher).flushWidget(token);
        verify(flusher, never()).flushPage(anyString());
    }

    @Test
    void flush_fallsBackToTheWidget_whenThePagesJwtIsRefused() {
        UUID owner = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        when(flusher.flushPage("old")).thenReturn(401);
        hub.subscribe(owner, sessionWithJwt("old"));
        hub.subscribeWidget(owner, token, "1.2.3.4", Duration.ofMinutes(10));

        verify(flusher).flushWidget(token);
    }

    @Test
    void flush_skipsAPageWhoseSessionIsGone() {
        UUID owner = UUID.randomUUID();
        MockHttpSession session = new MockHttpSession(null, "gone");
        session.setAttribute(ApiClient.SESSION_JWT_KEY, "j");
        hub.subscribe(owner, session);
        org.mockito.Mockito.clearInvocations(flusher);
        session.invalidate();

        hub.flush(owner);

        verifyNoInteractions(flusher);
    }

    @Test
    void flushesAskedWhileOneIsQueued_coalesceIntoOne() {
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        TwitchatDispatchHub async = new TwitchatDispatchHub(sse, flusher, queued::add);
        UUID owner = UUID.randomUUID();
        async.subscribe(owner, sessionWithJwt("j"));
        async.flush(owner);
        async.flush(owner);

        assertThat(queued).hasSize(1);
        queued.forEach(Runnable::run);
        verify(flusher, org.mockito.Mockito.times(1)).flushPage("j");
    }

    @Test
    void aFlushAskedWhileOneRuns_runsOnceMoreAfterIt_notInParallel() {
        TwitchatDispatchHub[] holder = new TwitchatDispatchHub[1];
        UUID owner = UUID.randomUUID();
        int[] calls = {0};
        when(flusher.flushPage("j")).thenAnswer(call -> {
            if (calls[0]++ == 0) {
                holder[0].flush(owner);
                holder[0].flush(owner);
                assertThat(calls[0]).isEqualTo(1);
            }
            return 200;
        });
        holder[0] = new TwitchatDispatchHub(sse, flusher, Runnable::run);

        holder[0].subscribe(owner, sessionWithJwt("j"));

        verify(flusher, org.mockito.Mockito.times(2)).flushPage("j");
    }

    @Test
    void anOwnerWhoseFlushEnded_canFlushAgain() {
        UUID owner = UUID.randomUUID();
        hub.subscribe(owner, sessionWithJwt("j"));
        hub.flush(owner);

        verify(flusher, org.mockito.Mockito.times(2)).flushPage("j");
    }

    @Test
    void flushesTalkToTheApi_atMostEightAtATime() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newCachedThreadPool();
        java.util.concurrent.atomic.AtomicInteger running = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger peak = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(20);
        when(flusher.flushPage(anyString())).thenAnswer(call -> {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            release.await();
            running.decrementAndGet();
            done.countDown();
            return 200;
        });
        TwitchatDispatchHub capped = new TwitchatDispatchHub(sse, flusher, pool);
        try {
            for (int i = 0; i < 20; i++) capped.subscribe(UUID.randomUUID(), sessionWithJwt("j"));
            Thread.sleep(300);
            assertThat(peak.get()).isEqualTo(TwitchatDispatchHub.MAX_FLUSHES);
            release.countDown();
            assertThat(done.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }
}
