package fr.enimaloc.catapult.service.debug;

import fr.enimaloc.catapult.common.dto.debug.ClientExchangeBatch;
import fr.enimaloc.catapult.common.dto.debug.ClientExchangeEntry;
import fr.enimaloc.catapult.domain.debug.DebugExchange.Direction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records in a member's debug journal the OBS and Twitchat exchanges their browser saw (the member's page or the OBS
 * widget), relayed by catapult-web in batches. The recorder masks other members and redacts secrets as for any other
 * exchange. An entry it cannot place (unknown service or direction) is skipped, and so is every entry beyond
 * {@value #PER_MINUTE} a member sends within a minute.
 */
@Slf4j
@Service
public class ClientDebugIngestService {

    /** What came of a batch. */
    public enum Result {
        /** Its entries were handed to the recorder, but the ones skipped. */
        RECORDED,
        /** The member is not in debug mode: nothing is recorded, and the browser stops capturing. */
        NOT_IN_DEBUG
    }

    /** The largest batch body read, in bytes. */
    public static final int MAX_BYTES = 256 * 1024;
    /** The most entries in one batch. */
    public static final int MAX_ENTRIES = 50;
    /** The most entries recorded per member within a rolling minute. */
    static final int PER_MINUTE = 600;
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final Set<String> SOURCES = Set.of("page", "widget");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** How each browser session is recorded: method and synthetic URL. */
    private record Session(String method, URI uri) {}

    private static final Map<String, Session> SESSIONS = Map.of(
            "obs", new Session("OBS", URI.create("obs://local")),
            "twitchat", new Session("TWITCHAT", URI.create("twitchat://obs")));

    private final DebugMemberRegistry registry;
    private final DebugExchangeRecorder recorder;
    private final Clock clock;
    /** The times of the entries each member had recorded within the last minute. */
    private final Map<UUID, Deque<Instant>> recent = new ConcurrentHashMap<>();

    @Autowired
    public ClientDebugIngestService(DebugMemberRegistry registry, DebugExchangeRecorder recorder) {
        this(registry, recorder, Clock.systemUTC());
    }

    ClientDebugIngestService(DebugMemberRegistry registry, DebugExchangeRecorder recorder, Clock clock) {
        this.registry = registry;
        this.recorder = recorder;
        this.clock = clock;
    }

    /**
     * The batch in {@code body}, its unreadable entries left out (an unreadable time is no time); empty when the
     * body is no batch, is over {@value #MAX_BYTES} bytes or holds over {@value #MAX_ENTRIES} entries.
     */
    public Optional<ClientExchangeBatch> read(byte[] body) {
        if (body == null || body.length > MAX_BYTES) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        JsonNode items = root.path("entries");
        if (!items.isMissingNode() && !items.isNull() && !items.isArray()) {
            return Optional.empty();
        }
        if (items.size() > MAX_ENTRIES) {
            return Optional.empty();
        }
        List<ClientExchangeEntry> entries = new ArrayList<>();
        for (JsonNode item : items) {
            ClientExchangeEntry entry = item.isObject() ? convert(item, ClientExchangeEntry.class) : null;
            if (entry != null) {
                entries.add(entry);
            }
        }
        JsonNode source = root.path("source");
        return Optional.of(new ClientExchangeBatch(source.isString() ? source.asString() : null,
                convert(root.path("sentAt"), Instant.class), entries));
    }

    /**
     * Records {@code batch}'s entries for {@code userId}, each moved by the clock skew the batch shows (the server's
     * time minus the browser's when it was sent).
     */
    public Result ingest(UUID userId, ClientExchangeBatch batch) {
        if (registry.modeOf(userId).isEmpty()) {
            recent.remove(userId);
            return Result.NOT_IN_DEBUG;
        }
        if (batch == null || batch.entries() == null) {
            return Result.RECORDED;
        }
        Instant now = clock.instant();
        Duration skew = batch.sentAt() == null ? Duration.ZERO : Duration.between(batch.sentAt(), now);
        HttpHeaders headers = sourceHeaders(batch.source());
        int skipped = 0;
        for (ClientExchangeEntry entry : batch.entries()) {
            Session session = entry == null || entry.service() == null ? null : SESSIONS.get(entry.service());
            Direction direction = entry == null ? null : direction(entry.direction());
            if (session == null || direction == null || !withinRate(userId, now)) {
                skipped++;
                continue;
            }
            Instant at = entry.at() == null ? now : entry.at().plus(skew);
            long duration = entry.durationMs() == null ? 0 : Math.max(0, entry.durationMs());
            RawExchange raw = new RawExchange(at, session.method(), session.uri(), headers, json(entry.request()),
                    entry.status(), null, json(entry.response()), entry.error(), duration);
            recorder.record(userId, direction, raw, entry.operation(), false, entry.service());
        }
        if (skipped > 0) {
            log.debug("Skipped {} of the {} browser debug exchanges of {}", skipped, batch.entries().size(), userId);
        }
        return Result.RECORDED;
    }

    /** Whether {@code userId} may have one more entry recorded at {@code now}; counts it when so. */
    private boolean withinRate(UUID userId, Instant now) {
        Deque<Instant> times = recent.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (times) {
            Instant oldest = now.minus(WINDOW);
            while (!times.isEmpty() && !times.peekFirst().isAfter(oldest)) {
                times.pollFirst();
            }
            if (times.size() >= PER_MINUTE) {
                return false;
            }
            times.addLast(now);
            return true;
        }
    }

    /** The request headers naming where the batch was captured; none for an unknown source. */
    private static HttpHeaders sourceHeaders(String source) {
        HttpHeaders headers = new HttpHeaders();
        if (source != null && SOURCES.contains(source)) {
            headers.set("source", source);
        }
        return HttpHeaders.readOnlyHttpHeaders(headers);
    }

    private static Direction direction(String name) {
        if ("IN".equals(name)) {
            return Direction.IN;
        }
        return "OUT".equals(name) ? Direction.OUT : null;
    }

    /** {@code body} as JSON bytes, or null for none. No content type goes with it: the free-text redaction applies. */
    private static byte[] json(Map<String, Object> body) {
        return body == null ? null : JSON.writeValueAsBytes(body);
    }

    /** {@code node} read as {@code type}, or null when it is missing or does not read as one. */
    private static <T> T convert(JsonNode node, Class<T> type) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            return JSON.treeToValue(node, type);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
