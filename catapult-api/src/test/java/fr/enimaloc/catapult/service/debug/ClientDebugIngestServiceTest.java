package fr.enimaloc.catapult.service.debug;

import fr.enimaloc.catapult.common.dto.debug.ClientExchangeBatch;
import fr.enimaloc.catapult.common.dto.debug.ClientExchangeEntry;
import fr.enimaloc.catapult.domain.debug.DebugExchange.Direction;
import fr.enimaloc.catapult.service.debug.ClientDebugIngestService.Result;
import fr.enimaloc.catapult.service.debug.DebugMemberRegistry.DebugMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ClientDebugIngestServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    private final DebugMemberRegistry registry = mock(DebugMemberRegistry.class);
    private final DebugExchangeRecorder recorder = mock(DebugExchangeRecorder.class);
    private final MovableClock clock = new MovableClock(NOW);
    private final ClientDebugIngestService service = new ClientDebugIngestService(registry, recorder, clock);
    private final UUID member = UUID.randomUUID();

    @BeforeEach
    void inDebug() {
        when(registry.modeOf(member)).thenReturn(Optional.of(new DebugMode(member, false)));
    }

    private static ClientExchangeEntry obsRequest(Instant at) {
        return new ClientExchangeEntry("obs", "OUT", "GetSceneList", at, 12L, 100,
                Map.of("requestType", "GetSceneList"), Map.of("scenes", List.of()), null);
    }

    private RawExchange recordedOne(Direction direction, String operation, String service) {
        ArgumentCaptor<RawExchange> raw = ArgumentCaptor.forClass(RawExchange.class);
        verify(recorder).record(eq(member), eq(direction), raw.capture(), eq(operation), eq(false), eq(service));
        return raw.getValue();
    }

    @Test
    void recordsAnObsRequestWithItsResponse() {
        Result result = service.ingest(member, new ClientExchangeBatch("page", NOW, List.of(obsRequest(NOW))));

        assertThat(result).isEqualTo(Result.RECORDED);
        RawExchange raw = recordedOne(Direction.OUT, "GetSceneList", "obs");
        assertThat(raw.method()).isEqualTo("OBS");
        assertThat(raw.uri()).isEqualTo(URI.create("obs://local"));
        assertThat(raw.requestHeaders().getFirst("source")).isEqualTo("page");
        assertThat(raw.requestHeaders().getContentType()).isNull();
        assertThat(new String(raw.requestBody(), StandardCharsets.UTF_8))
                .isEqualTo("{\"requestType\":\"GetSceneList\"}");
        assertThat(new String(raw.responseBody(), StandardCharsets.UTF_8)).isEqualTo("{\"scenes\":[]}");
        assertThat(raw.status()).isEqualTo(100);
        assertThat(raw.error()).isNull();
        assertThat(raw.durationMs()).isEqualTo(12L);
        assertThat(raw.at()).isEqualTo(NOW);
        assertThat(raw.responseHeaders()).isNull();
    }

    @Test
    void recordsATwitchatTimeout() {
        ClientExchangeEntry action = new ClientExchangeEntry("twitchat", "OUT", "GET_CURRENT_TIMERS", NOW, null, null,
                Map.of("type", "GET_CURRENT_TIMERS"), null, "timeout");

        service.ingest(member, new ClientExchangeBatch("widget", NOW, List.of(action)));

        RawExchange raw = recordedOne(Direction.OUT, "GET_CURRENT_TIMERS", "twitchat");
        assertThat(raw.method()).isEqualTo("TWITCHAT");
        assertThat(raw.uri()).isEqualTo(URI.create("twitchat://obs"));
        assertThat(raw.requestHeaders().getFirst("source")).isEqualTo("widget");
        assertThat(raw.status()).isNull();
        assertThat(raw.error()).isEqualTo("timeout");
        assertThat(raw.responseBody()).isNull();
        assertThat(raw.durationMs()).isZero();
    }

    @Test
    void appliesTheBatchClockSkew() {
        // The browser runs 90 s late: its entries are moved forward by as much
        Instant sentAt = NOW.minusSeconds(90);
        service.ingest(member, new ClientExchangeBatch("page", sentAt, List.of(obsRequest(sentAt.minusSeconds(3)))));

        assertThat(recordedOne(Direction.OUT, "GetSceneList", "obs").at()).isEqualTo(NOW.minusSeconds(3));
    }

    @Test
    void refusesWhenNotInDebug() {
        when(registry.modeOf(member)).thenReturn(Optional.empty());

        Result result = service.ingest(member, new ClientExchangeBatch("page", NOW, List.of(obsRequest(NOW))));

        assertThat(result).isEqualTo(Result.NOT_IN_DEBUG);
        verifyNoInteractions(recorder);
    }

    @Test
    void skipsUnknownServicesAndDirections() {
        List<ClientExchangeEntry> entries = new ArrayList<>();
        entries.add(new ClientExchangeEntry("discord", "OUT", "x", NOW, null, null, null, null, null));
        entries.add(new ClientExchangeEntry("obs", "SIDEWAYS", "x", NOW, null, null, null, null, null));
        entries.add(new ClientExchangeEntry(null, null, "x", NOW, null, null, null, null, null));
        entries.add(null);
        entries.add(new ClientExchangeEntry("obs", "IN", "dropped", null, null, null, Map.of("dropped", 4), null,
                null));

        Result result = service.ingest(member, new ClientExchangeBatch("page", null, entries));

        assertThat(result).isEqualTo(Result.RECORDED);
        RawExchange raw = recordedOne(Direction.IN, "dropped", "obs");
        assertThat(raw.at()).as("no time: the server's").isEqualTo(NOW);
        assertThat(new String(raw.requestBody(), StandardCharsets.UTF_8)).isEqualTo("{\"dropped\":4}");
    }

    @Test
    void anUnknownSourceIsNotNamed() {
        service.ingest(member, new ClientExchangeBatch("elsewhere", NOW, List.of(obsRequest(NOW))));

        assertThat(recordedOne(Direction.OUT, "GetSceneList", "obs").requestHeaders().containsHeader("source"))
                .isFalse();
    }

    @Test
    void capsTheRatePerMember() {
        List<ClientExchangeEntry> fifty = Collections.nCopies(50, obsRequest(NOW));
        for (int i = 0; i < 12; i++) {
            service.ingest(member, new ClientExchangeBatch("page", NOW, fifty));
            clock.advance(Duration.ofSeconds(4));
        }
        verify(recorder, times(600)).record(eq(member), any(), any(), anyString(), anyBoolean(), anyString());

        // The 601st entry within the minute is skipped
        service.ingest(member, new ClientExchangeBatch("page", clock.instant(), List.of(obsRequest(clock.instant()))));
        verify(recorder, times(600)).record(eq(member), any(), any(), anyString(), anyBoolean(), anyString());

        // Once the first batch is more than a minute old, its share is available again
        clock.advance(Duration.ofSeconds(13));
        service.ingest(member, new ClientExchangeBatch("page", clock.instant(), fifty));
        verify(recorder, times(650)).record(eq(member), any(), any(), anyString(), anyBoolean(), anyString());
    }

    @Test
    void theRateIsCountedPerMember() {
        UUID other = UUID.randomUUID();
        when(registry.modeOf(other)).thenReturn(Optional.of(new DebugMode(other, false)));
        List<ClientExchangeEntry> fifty = Collections.nCopies(50, obsRequest(NOW));
        for (int i = 0; i < 12; i++) {
            service.ingest(member, new ClientExchangeBatch("page", NOW, fifty));
        }

        service.ingest(other, new ClientExchangeBatch("page", NOW, List.of(obsRequest(NOW))));

        verify(recorder).record(eq(other), any(), any(), anyString(), anyBoolean(), anyString());
    }

    @Test
    void readsABatchAndSkipsTheEntriesItCannotRead() {
        String json = """
                {"source":"page","sentAt":"2026-11-20T12:00:00Z","entries":[
                  {"service":"obs","direction":"OUT","operation":"GetSceneList","at":"2026-11-20T11:59:59Z",
                   "durationMs":3,"status":100,"request":{"requestType":"GetSceneList"},"response":{"scenes":[]}},
                  {"service":"obs","direction":"IN","at":"not a time"},
                  {"service":"obs","direction":"IN","request":[1,2]},
                  "nonsense"
                ]}""";

        Optional<ClientExchangeBatch> batch = service.read(json.getBytes(StandardCharsets.UTF_8));

        assertThat(batch).isPresent();
        assertThat(batch.get().source()).isEqualTo("page");
        assertThat(batch.get().sentAt()).isEqualTo(NOW);
        assertThat(batch.get().entries()).singleElement()
                .satisfies(e -> {
                    assertThat(e.operation()).isEqualTo("GetSceneList");
                    assertThat(e.status()).isEqualTo(100);
                    assertThat(e.request()).containsEntry("requestType", "GetSceneList");
                });
    }

    @Test
    void readsAnUnreadableTimeAsNoTime() {
        Optional<ClientExchangeBatch> batch = service.read(
                "{\"source\":\"page\",\"sentAt\":\"yesterday\",\"entries\":[]}".getBytes(StandardCharsets.UTF_8));

        assertThat(batch).hasValueSatisfying(b -> {
            assertThat(b.sentAt()).isNull();
            assertThat(b.entries()).isEmpty();
        });
    }

    @Test
    void refusesWhatIsNoBatch() {
        assertThat(service.read(null)).isEmpty();
        assertThat(service.read("not json".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(service.read("[]".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(service.read("{\"entries\":{}}".getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    @Test
    void refusesABatchOverItsLimits() {
        StringBuilder many = new StringBuilder("{\"entries\":[");
        for (int i = 0; i <= ClientDebugIngestService.MAX_ENTRIES; i++) {
            many.append(i == 0 ? "" : ",").append("{}");
        }
        many.append("]}");
        assertThat(service.read(many.toString().getBytes(StandardCharsets.UTF_8))).isEmpty();

        byte[] huge = ("{\"source\":\"" + "x".repeat(ClientDebugIngestService.MAX_BYTES) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(service.read(huge)).isEmpty();
    }

    @Test
    void aBatchWithoutEntriesRecordsNothing() {
        assertThat(service.ingest(member, new ClientExchangeBatch("page", NOW, null))).isEqualTo(Result.RECORDED);

        verifyNoInteractions(recorder);
    }

    /** A clock the test moves forward. */
    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
