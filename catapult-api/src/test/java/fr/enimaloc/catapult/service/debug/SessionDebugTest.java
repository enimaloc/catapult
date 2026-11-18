package fr.enimaloc.catapult.service.debug;

import fr.enimaloc.catapult.domain.debug.DebugExchange.Direction;
import fr.enimaloc.catapult.service.debug.DebugMemberRegistry.DebugMode;
import fr.enimaloc.catapult.service.debug.SessionDebug.LinkedAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What a bot session exchanges is recorded for the members in debug mode it concerns, and only for them. */
class SessionDebugTest {

    private static final URI SESSION = URI.create("wss://session.test");
    private static final UUID BOT = UUID.randomUUID();
    private static final UUID OTHER_BOT = UUID.randomUUID();

    private final DebugMemberRegistry registry = mock(DebugMemberRegistry.class);
    private final DebugExchangeRecorder recorder = mock(DebugExchangeRecorder.class);
    private final Map<UUID, LinkedAccount> links = new HashMap<>();
    private final AtomicInteger lookups = new AtomicInteger();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private Instant now = Instant.parse("2026-11-18T10:00:00Z");
    private SessionDebug debug;

    @BeforeEach
    void setUp() {
        Function<UUID, Optional<LinkedAccount>> lookup = userId -> {
            lookups.incrementAndGet();
            return Optional.ofNullable(links.get(userId));
        };
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
        debug = new SessionDebug(registry, recorder, "svc", SESSION, lookup, clock);
        links.put(alice, new LinkedAccount(BOT, Set.of("k-alice")));
        links.put(bob, new LinkedAccount(OTHER_BOT, Set.of("k-bob")));
        inDebug(alice, bob);
    }

    private void inDebug(UUID... members) {
        when(registry.hasAny()).thenReturn(members.length > 0);
        when(registry.members()).thenReturn(Set.of(members));
        for (UUID member : members) {
            when(registry.modeOf(member)).thenReturn(Optional.of(new DebugMode(member, false)));
        }
    }

    private RawExchange recorded(UUID userId, Direction direction, String operation, boolean filtered) {
        ArgumentCaptor<RawExchange> raw = ArgumentCaptor.forClass(RawExchange.class);
        verify(recorder).record(eq(userId), eq(direction), raw.capture(), eq(operation), eq(filtered), eq("svc"));
        return raw.getValue();
    }

    private static String text(byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void inbound_recordedForTheMemberItNames_onThatBotOnly() {
        debug.inbound(BOT, "k-alice k-bob"::contains, "WS", () -> "k-alice k-bob", () -> "op");

        RawExchange raw = recorded(alice, Direction.IN, "op", false);
        assertThat(raw.method()).isEqualTo("WS");
        assertThat(raw.uri()).isEqualTo(SESSION);
        assertThat(text(raw.requestBody())).isEqualTo("k-alice k-bob");
        // bob is named too, but his link is on another bot
        verify(recorder, never()).record(eq(bob), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void inbound_namingNoMemberInDebug_neitherBuiltNorRecorded() {
        debug.inbound(BOT, "k-carol"::equals, "WS", () -> {
            throw new AssertionError("built");
        }, () -> "op");

        verify(recorder, never()).record(any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void noMemberInDebug_linksNeverRead() {
        inDebug();

        assertThat(debug.watches(BOT)).isFalse();
        debug.inbound(BOT, key -> true, "WS", () -> "x", () -> "op");

        assertThat(lookups).hasValue(0);
        verify(recorder, never()).record(any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void links_readAgainOnlyWhenTheMembersChangeOrAfterTheRefresh() {
        assertThat(debug.watches(BOT)).isTrue();
        assertThat(debug.watches(OTHER_BOT)).isTrue();
        assertThat(lookups).hasValue(2);

        now = now.plus(SessionDebug.REFRESH).minusSeconds(1);
        debug.watches(BOT);
        assertThat(lookups).hasValue(2);

        now = now.plusSeconds(1);
        debug.watches(BOT);
        assertThat(lookups).hasValue(4);

        inDebug(alice);
        assertThat(debug.watches(OTHER_BOT)).isFalse();
        assertThat(lookups).hasValue(5);
    }

    @Test
    void aLinkThatCannotBeRead_skipped() {
        Function<UUID, Optional<LinkedAccount>> failing = userId -> {
            throw new IllegalStateException("db down");
        };
        SessionDebug broken = new SessionDebug(registry, recorder, "svc", SESSION, failing);

        assertThat(broken.watches(BOT)).isFalse();
    }

    @Test
    void outbound_recordsTheCommandAndItsAnswer() {
        CompletableFuture<String> sent = new CompletableFuture<>();

        CompletableFuture<String> returned = debug.outbound(alice, "BGS", "bgs.cmd", () -> "cmd(1)",
                answer -> "answer " + answer, () -> sent);
        sent.complete("ok");

        assertThat(returned).isSameAs(sent);
        RawExchange raw = recorded(alice, Direction.OUT, "bgs.cmd", false);
        assertThat(text(raw.requestBody())).isEqualTo("cmd(1)");
        assertThat(text(raw.responseBody())).isEqualTo("answer ok");
        assertThat(raw.error()).isNull();
    }

    @Test
    void outbound_failure_recordedAsTheError() {
        debug.outbound(alice, "BGS", "bgs.cmd", () -> "cmd(1)", answer -> "x",
                () -> CompletableFuture.failedFuture(new IllegalStateException("refused")));

        RawExchange raw = recorded(alice, Direction.OUT, "bgs.cmd", false);
        assertThat(raw.responseBody()).isNull();
        assertThat(raw.error()).contains("refused");
    }

    @Test
    void outbound_thrownBySending_recordedThenRethrown() {
        assertThatThrownBy(() -> debug.outbound(alice, "BGS", "bgs.cmd", () -> "cmd(1)", answer -> "x",
                () -> {
                    throw new IllegalStateException("no session");
                })).hasMessage("no session");

        assertThat(recorded(alice, Direction.OUT, "bgs.cmd", false).error()).contains("no session");
    }

    @Test
    void outbound_memberNotInDebug_onlyRunsTheCall() {
        UUID carol = UUID.randomUUID();
        when(registry.modeOf(carol)).thenReturn(Optional.empty());

        CompletableFuture<String> result = debug.outbound(carol, "BGS", "bgs.cmd", () -> {
            throw new AssertionError("built");
        }, answer -> "x", () -> CompletableFuture.completedFuture("ok"));

        assertThat(result).isCompletedWithValue("ok");
        verify(recorder, never()).record(any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void batch_eachMemberOfTheBotGetsItsShare() {
        RawExchange call = new RawExchange(Instant.EPOCH, "GET", URI.create("https://svc.test/friends"), null, null,
                200, null, "k-alice,k-carol".getBytes(StandardCharsets.UTF_8), null, 3);

        String result = debug.batch(BOT, "svc.friends", (raw, key) -> raw, () -> {
            DebugContext.record(call);
            return "done";
        });

        assertThat(result).isEqualTo("done");
        verify(recorder).record(alice, Direction.OUT, call, "svc.friends", true);
        verify(recorder, never()).record(eq(bob), any(), any(), any(), anyBoolean());
    }

    @Test
    void off_recordsNothing() {
        SessionDebug.OFF.inbound(BOT, key -> true, "WS", () -> "x", () -> "op");

        assertThat(SessionDebug.OFF.watches(BOT)).isFalse();
        assertThat(SessionDebug.OFF.outbound(alice, "BGS", "op", () -> "x", answer -> "x",
                () -> CompletableFuture.completedFuture("ok"))).isCompletedWithValue("ok");
    }
}
