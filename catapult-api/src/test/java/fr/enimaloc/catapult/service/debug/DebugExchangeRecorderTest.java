package fr.enimaloc.catapult.service.debug;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import fr.enimaloc.catapult.domain.debug.DebugExchange;
import fr.enimaloc.catapult.domain.debug.DebugExchange.Direction;
import fr.enimaloc.catapult.repository.debug.DebugExchangeRepository;
import fr.enimaloc.catapult.service.debug.DebugMemberRegistry.DebugMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DebugExchangeRecorderTest {

    private final UUID member = UUID.randomUUID();
    private final DebugMemberRegistry registry = mock(DebugMemberRegistry.class);
    private final OtherMemberMasker masker = mock(OtherMemberMasker.class);
    private final DebugExchangeRepository repository = mock(DebugExchangeRepository.class);
    private final ExternalServices services = new ExternalServices("https://git.enimaloc.fr");
    private final List<DebugExchangeListener> listeners = new ArrayList<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(DebugExchangeRecorder.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Executor executor = Runnable::run;
    private TraceIdSource traceIds = () -> null;

    @BeforeEach
    void setUp() {
        when(registry.modeOf(any())).thenReturn(Optional.empty());
        when(masker.mask(any(), any())).thenAnswer(inv -> {
            String text = inv.getArgument(0);
            return text == null ? null : text.replace("bob", "[membre]");
        });
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    private DebugExchangeRecorder recorder() {
        return new DebugExchangeRecorder(registry, new DebugRedactor(), masker, repository, services,
                listeners, traceIds, executor);
    }

    private void debugged(boolean reveal) {
        when(registry.modeOf(member)).thenReturn(Optional.of(new DebugMode(member, reveal)));
    }

    private static RawExchange raw(String url, String requestBody, String responseBody, String error) {
        HttpHeaders req = new HttpHeaders();
        req.add("Authorization", "Bearer s3cret");
        req.add("X-Other", "bob");
        req.setContentType(MediaType.APPLICATION_JSON);
        HttpHeaders res = new HttpHeaders();
        res.setContentType(MediaType.APPLICATION_JSON);
        return new RawExchange(Instant.parse("2026-10-24T10:00:00Z"), "POST", URI.create(url),
                req, requestBody == null ? null : requestBody.getBytes(StandardCharsets.UTF_8),
                error == null ? 200 : null, error == null ? res : null,
                responseBody == null ? null : responseBody.getBytes(StandardCharsets.UTF_8), error, 42);
    }

    private DebugExchange saved() {
        ArgumentCaptor<DebugExchange> captor = ArgumentCaptor.forClass(DebugExchange.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void debuggedSubject_callIsRecorded() {
        debugged(false);
        traceIds = () -> "trace-1";

        recorder().record(member, Direction.OUT, raw("https://api.twitch.tv/helix/users?login=bob&access_token=tok",
                "{\"client_secret\":\"xyz\",\"who\":\"bob\"}", "{\"name\":\"bob\"}", null), "op", true);

        DebugExchange e = saved();
        assertThat(e.getUserId()).isEqualTo(member);
        assertThat(e.getOccurredAt()).isEqualTo(Instant.parse("2026-10-24T10:00:00Z"));
        assertThat(e.getDirection()).isEqualTo(Direction.OUT);
        assertThat(e.getService()).isEqualTo("twitch");
        assertThat(e.getOperation()).isEqualTo("op");
        assertThat(e.getMethod()).isEqualTo("POST");
        assertThat(e.getUrl()).isEqualTo("https://api.twitch.tv/helix/users?login=[membre]&access_token=[masqué]");
        assertThat(e.getRequestHeaders()).contains("Authorization: [masqué]").contains("X-Other: [membre]");
        assertThat(e.getRequestBody()).contains("[masqué]").contains("[membre]").doesNotContain("xyz");
        assertThat(e.getStatus()).isEqualTo(200);
        assertThat(e.getResponseHeaders()).contains("Content-Type: application/json");
        assertThat(e.getResponseBody()).isEqualTo("{\"name\":\"[membre]\"}");
        assertThat(e.getError()).isNull();
        assertThat(e.getDurationMs()).isEqualTo(42);
        assertThat(e.isFiltered()).isTrue();
        assertThat(e.getTraceId()).isEqualTo("trace-1");
    }

    @Test
    void revealSecrets_keepsSecretsButStillMasksOtherMembers() {
        debugged(true);

        recorder().record(member, Direction.OUT, raw("https://api.twitch.tv/x?access_token=tok&u=bob", null, null, null),
                null, false);

        DebugExchange e = saved();
        assertThat(e.getUrl()).isEqualTo("https://api.twitch.tv/x?access_token=tok&u=[membre]");
        assertThat(e.getRequestHeaders()).contains("Authorization: Bearer s3cret");
        assertThat(e.getRequestBody()).isNull();
    }

    @Test
    void secretsAreRedactedBeforeMembersAreMasked() {
        debugged(false);
        // A masker that would expose the secret if it saw it: the redactor must have run first.
        when(masker.mask(any(), any())).thenAnswer(inv -> inv.getArgument(0));

        recorder().record(member, Direction.OUT, raw("https://x.example/a", null, null, null), null, false);

        verify(masker, never()).mask(argThat(s -> s != null && s.contains("s3cret")), any());
        verify(masker).mask(eq("https://x.example/a"), eq(member));
    }

    @Test
    void callFailure_isStoredWithError() {
        debugged(false);

        recorder().record(member, Direction.OUT, raw("https://api.igdb.com/v4/games", null, null, "IOException: refused"),
                null, false);

        DebugExchange e = saved();
        assertThat(e.getService()).isEqualTo("igdb");
        assertThat(e.getStatus()).isNull();
        assertThat(e.getError()).isEqualTo("IOException: refused");
        assertThat(e.isError()).isTrue();
    }

    @Test
    void memberNoLongerDebugged_nothingStored() {
        recorder().record(member, Direction.OUT, raw("https://api.twitch.tv/x", null, null, null), null, false);

        verify(repository, never()).save(any());
    }

    @Test
    void storeFailure_neverReachesTheCaller() {
        debugged(false);
        when(repository.save(any())).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> recorder().record(member, Direction.OUT, raw("https://api.twitch.tv/x", null, null, null),
                null, false)).doesNotThrowAnyException();

        assertThat(logs.list).anySatisfy(ev -> {
            assertThat(ev.getLevel().toString()).isEqualTo("WARN");
            assertThat(ev.getFormattedMessage()).contains("debug exchange");
        });
    }

    @Test
    void redactionFailure_neverReachesTheCaller() {
        debugged(false);
        when(masker.mask(anyString(), any())).thenThrow(new IllegalStateException("bad regex"));

        assertThatCode(() -> recorder().record(member, Direction.OUT, raw("https://api.twitch.tv/x", null, null, null),
                null, false)).doesNotThrowAnyException();
        verify(repository, never()).save(any());
    }

    @Test
    void rejectedExecution_neverReachesTheCaller() {
        debugged(false);
        executor = task -> { throw new java.util.concurrent.RejectedExecutionException("closed"); };

        assertThatCode(() -> recorder().record(member, Direction.OUT, raw("https://api.twitch.tv/x", null, null, null),
                null, false)).doesNotThrowAnyException();
    }

    @Test
    void listenersAreNotified_asTheSubject_andAThrowingOneDoesNotStopTheOthers() {
        debugged(false);
        AtomicReference<UUID> seenSubject = new AtomicReference<>();
        List<DebugExchange> seen = new ArrayList<>();
        listeners.add((userId, e) -> { throw new IllegalStateException("listener down"); });
        listeners.add((userId, e) -> {
            seenSubject.set(DebugContext.subject().orElse(null));
            seen.add(e);
        });

        recorder().record(member, Direction.OUT, raw("https://api.twitch.tv/x", null, null, null), null, false);

        assertThat(seen).hasSize(1);
        assertThat(seenSubject.get()).isEqualTo(member);
        assertThat(logs.list).anySatisfy(ev -> assertThat(ev.getLevel().toString()).isEqualTo("WARN"));
    }

    @Test
    void recordReturnsBeforeTheWorkIsDone() throws InterruptedException {
        debugged(false);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        when(repository.save(any())).thenAnswer(inv -> {
            release.await(5, TimeUnit.SECONDS);
            done.countDown();
            return inv.getArgument(0);
        });
        executor = task -> Thread.ofVirtual().start(task);

        recorder().record(member, Direction.OUT, raw("https://api.twitch.tv/x", null, null, null), null, false);

        assertThat(done.getCount()).isEqualTo(1);
        release.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void serviceNames() {
        assertThat(services.of(URI.create("https://twitch.tv/x"))).isEqualTo("twitch");
        assertThat(services.of(URI.create("https://api.twitch.tv/helix"))).isEqualTo("twitch");
        assertThat(services.of(URI.create("https://id.twitch.tv/oauth2/token"))).isEqualTo("twitch");
        assertThat(services.of(URI.create("wss://eventsub.wss.twitch.tv/ws"))).isEqualTo("twitch");
        assertThat(services.of(URI.create("https://api.steampowered.com/ISteamUser"))).isEqualTo("steam");
        assertThat(services.of(URI.create("https://store.steampowered.com/api"))).isEqualTo("steam");
        assertThat(services.of(URI.create("https://steamcommunity.com/openid/login"))).isEqualTo("steam");
        assertThat(services.of(URI.create("https://api.igdb.com/v4/games"))).isEqualTo("igdb");
        assertThat(services.of(URI.create("https://titlehub.xboxlive.com/users"))).isEqualTo("xbox");
        assertThat(services.of(URI.create("https://peoplehub.xboxlive.com/users"))).isEqualTo("xbox");
        assertThat(services.of(URI.create("https://www.xbox.com/fr-FR"))).isEqualTo("xbox");
        assertThat(services.of(URI.create("https://api.minecraftservices.com/minecraft/profile"))).isEqualTo("minecraft");
        assertThat(services.of(URI.create("https://sessionserver.mojang.com/session"))).isEqualTo("minecraft");
        assertThat(services.of(URI.create("https://login.live.com/oauth20_token.srf"))).isEqualTo("minecraft");
        assertThat(services.of(URI.create("https://login.microsoftonline.com/consumers"))).isEqualTo("minecraft");
        assertThat(services.of(URI.create("https://user.auth.xboxlive.com/user/authenticate"))).isEqualTo("minecraft");
        assertThat(services.of(URI.create("https://xsts.auth.xboxlive.com/xsts/authorize"))).isEqualTo("minecraft");
        assertThat(services.of(URI.create("https://www.doesthedogdie.com/dddsearch"))).isEqualTo("dtdd");
        assertThat(services.of(URI.create("https://git.enimaloc.fr/api/v4/projects"))).isEqualTo("gitlab");
        assertThat(services.of(URI.create("https://GIT.enimaloc.fr/api"))).isEqualTo("gitlab");
        assertThat(services.of(URI.create("https://example.org/x"))).isEqualTo("example.org");
        assertThat(services.of(URI.create("https://nottwitch.tv/x"))).isEqualTo("nottwitch.tv");
    }

    @Test
    void longUnknownHost_fitsTheServiceColumn() {
        debugged(false);
        String host = "a".repeat(40) + ".example.org";

        recorder().record(member, Direction.OUT, raw("https://" + host + "/x", null, null, null), null, false);

        assertThat(saved().getService()).hasSize(32);
    }

    @Test
    void error_isRedactedThenMasked() {
        debugged(false);

        recorder().record(member, Direction.OUT, raw("https://api.twitch.tv/x", null, null,
                "IOException: https://api.twitch.tv/x?access_token=tok&login=bob"), null, false);

        assertThat(saved().getError()).isEqualTo("IOException: https://api.twitch.tv/x?access_token=[masqué]&login=[membre]");
    }

    @Test
    void error_keepsSecretsWhenRevealed() {
        debugged(true);

        recorder().record(member, Direction.OUT, raw("https://api.twitch.tv/x", null, null,
                "IOException: https://api.twitch.tv/x?access_token=tok"), null, false);

        assertThat(saved().getError()).isEqualTo("IOException: https://api.twitch.tv/x?access_token=tok");
    }

    @Test
    void queueFull_exchangeDropped_callerReturnsAtOnce_oneWarn() throws InterruptedException {
        debugged(false);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(repository.save(any())).thenAnswer(inv -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return inv.getArgument(0);
        });
        var bounded = DebugExchangeRecorder.boundedExecutor(1, 1);
        executor = bounded;
        DebugExchangeRecorder recorder = recorder();
        try {
            recorder.record(member, Direction.OUT, raw("https://api.twitch.tv/1", null, null, null), null, false);
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            recorder.record(member, Direction.OUT, raw("https://api.twitch.tv/2", null, null, null), null, false);

            long t0 = System.nanoTime();
            assertThatCode(() -> {
                recorder.record(member, Direction.OUT, raw("https://api.twitch.tv/3", null, null, null), null, false);
                recorder.record(member, Direction.OUT, raw("https://api.twitch.tv/4", null, null, null), null, false);
            }).doesNotThrowAnyException();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(1000);
            assertThat(logs.list.stream().filter(ev -> ev.getFormattedMessage().contains("dropped"))).hasSize(1);
        } finally {
            release.countDown();
            recorder.close();
        }
        verify(repository, org.mockito.Mockito.times(2)).save(any());
    }
}
