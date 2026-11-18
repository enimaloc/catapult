package fr.enimaloc.catapult.service.debug;

import fr.enimaloc.catapult.domain.debug.DebugExchange.Direction;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Records, for the members in debug mode, what a bot session (Epic Connect, Battle.net BGS, Ankama chat) receives
 * and sends for them. Those go through a service account shared by many members, and most of them are not HTTP:
 * the outbound interceptor attributes none of them. A member is known to the session by keys (its account id on the
 * service, its tag...) read from its link and kept for {@link #REFRESH}, so that finding out whom a message concerns
 * costs a map lookup, and nothing at all while no member is in debug mode. Nothing here ever fails or holds up the
 * session.
 */
@Slf4j
public class SessionDebug {

    static final Duration REFRESH = Duration.ofSeconds(30);

    /** Records nothing: for code and tests that build the sessions by hand. */
    public static final SessionDebug OFF = new SessionDebug(null, null, "none", null, userId -> Optional.empty());

    /** How a member is known to a session: the bot that befriended it, and the keys naming it in what it exchanges. */
    public record LinkedAccount(UUID serviceAccountId, Set<String> keys) {
        public LinkedAccount {
            keys = Set.copyOf(keys);
        }
    }

    private record Snapshot(Set<UUID> members, Instant at, Map<UUID, LinkedAccount> links) {
    }

    private final DebugMemberRegistry registry;
    private final DebugExchangeRecorder recorder;
    private final DebugRecording recording;
    private final String service;
    private final URI uri;
    private final Function<UUID, Optional<LinkedAccount>> lookup;
    private final Clock clock;
    private volatile Snapshot snapshot;

    /**
     * @param service the service name of the recorded exchanges ({@code epic}, {@code battlenet}, {@code ankama})
     * @param uri     where the session is connected to
     * @param lookup  how a member is known to the session, empty when it is not linked; read outside any transaction
     */
    public SessionDebug(DebugMemberRegistry registry, DebugExchangeRecorder recorder, String service, URI uri,
                        Function<UUID, Optional<LinkedAccount>> lookup) {
        this(registry, recorder, service, uri, lookup, Clock.systemUTC());
    }

    SessionDebug(DebugMemberRegistry registry, DebugExchangeRecorder recorder, String service, URI uri,
                 Function<UUID, Optional<LinkedAccount>> lookup, Clock clock) {
        this.registry = registry;
        this.recorder = recorder;
        this.recording = new DebugRecording(registry, recorder);
        this.service = service;
        this.uri = uri;
        this.lookup = lookup;
        this.clock = clock;
    }

    /** Whether a member in debug mode is linked to {@code serviceAccountId}: lets a caller skip building a message. */
    public boolean watches(UUID serviceAccountId) {
        return !linked(serviceAccountId).isEmpty();
    }

    /**
     * Runs {@code call}, an HTTP call the bot {@code serviceAccountId} makes for all its members at once: each member
     * in debug mode linked to it gets its own share of it, as {@link DebugRecording#batch} records it.
     */
    public <T> T batch(UUID serviceAccountId, String operation, BiFunction<RawExchange, String, RawExchange> share,
                       Supplier<T> call) {
        return recording.batch(operation, members(serviceAccountId), share, call);
    }

    /** The members in debug mode linked to {@code serviceAccountId}, one per key. */
    private List<DebugRecording.Member> members(UUID serviceAccountId) {
        return linked(serviceAccountId).stream()
                .flatMap(e -> e.getValue().keys().stream().map(key -> new DebugRecording.Member(e.getKey(), key)))
                .toList();
    }

    /**
     * Records a message the session of {@code serviceAccountId} received, for each member in debug mode it
     * concerns: those with a key {@code concerns} accepts.
     *
     * @param method    {@code WS} for a WebSocket message, else the protocol's name
     * @param body      the message; only evaluated when it concerns a member in debug mode
     * @param operation names the message; only evaluated when it concerns a member in debug mode, may return null
     */
    public void inbound(UUID serviceAccountId, Predicate<String> concerns, String method, Supplier<String> body,
                        Supplier<String> operation) {
        try {
            List<UUID> concerned = linked(serviceAccountId).stream()
                    .filter(e -> e.getValue().keys().stream().anyMatch(concerns))
                    .map(Map.Entry::getKey)
                    .toList();
            if (concerned.isEmpty()) {
                return;
            }
            String text = body.get();
            RawExchange raw = new RawExchange(clock.instant(), method, uri, null, bytes(text), null, null, null,
                    null, 0);
            String name = nameOf(operation);
            concerned.forEach(userId -> recorder.record(userId, Direction.IN, raw, name, false, service));
        } catch (RuntimeException e) {
            log.warn("Could not record a {} message for the members in debug mode: {}", service, e.toString());
        }
    }

    /**
     * Runs {@code call}, a command the session sends for {@code userId}, and records it with its outcome when that
     * member is in debug mode. The future returned is {@code call}'s own.
     *
     * @param method    the protocol's name
     * @param operation names the command
     * @param request   what is sent; only evaluated for a member in debug mode
     * @param response  describes the answer, may return null
     */
    public <T> CompletableFuture<T> outbound(UUID userId, String method, String operation, Supplier<String> request,
                                             Function<? super T, String> response,
                                             Supplier<CompletableFuture<T>> call) {
        if (!inDebug(userId)) {
            return call.get();
        }
        Instant at = clock.instant();
        long started = System.nanoTime();
        String sent = textOf(request);
        CompletableFuture<T> future;
        try {
            future = call.get();
        } catch (RuntimeException e) {
            recordOut(userId, method, operation, at, started, sent, null, e);
            throw e;
        }
        future.whenComplete((result, failure) -> recordOut(userId, method, operation, at, started, sent,
                result == null ? null : textOf(() -> response.apply(result)), failure));
        return future;
    }

    private void recordOut(UUID userId, String method, String operation, Instant at, long started, String sent,
                           String received, Throwable failure) {
        try {
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause() : failure;
            RawExchange raw = new RawExchange(at, method, uri, null, bytes(sent), null, null, bytes(received),
                    cause == null ? null : cause.toString(), elapsedMs);
            recorder.record(userId, Direction.OUT, raw, operation, false, service);
        } catch (RuntimeException e) {
            log.warn("Could not record a {} command of {}: {}", service, userId, e.toString());
        }
    }

    private boolean inDebug(UUID userId) {
        if (registry == null || recorder == null || userId == null) {
            return false;
        }
        try {
            return registry.modeOf(userId).isPresent();
        } catch (RuntimeException e) {
            log.warn("Could not tell whether {} is in debug mode: {}", userId, e.toString());
            return false;
        }
    }

    private List<Map.Entry<UUID, LinkedAccount>> linked(UUID serviceAccountId) {
        if (registry == null || recorder == null || serviceAccountId == null) {
            return List.of();
        }
        try {
            if (!registry.hasAny()) {
                return List.of();
            }
            return current().links().entrySet().stream()
                    .filter(e -> serviceAccountId.equals(e.getValue().serviceAccountId()))
                    .toList();
        } catch (RuntimeException e) {
            log.warn("Could not tell which {} members are in debug mode: {}", service, e.toString());
            return List.of();
        }
    }

    /** The links of the members in debug mode, read again when they change or after {@link #REFRESH}. */
    private Snapshot current() {
        Set<UUID> members = registry.members();
        Snapshot s = snapshot;
        if (fresh(s, members)) {
            return s;
        }
        synchronized (this) {
            s = snapshot;
            if (fresh(s, members)) {
                return s;
            }
            Map<UUID, LinkedAccount> links = new HashMap<>();
            for (UUID member : members) {
                try {
                    lookup.apply(member).ifPresent(link -> links.put(member, link));
                } catch (RuntimeException e) {
                    log.warn("Could not read the {} link of {} for the debug recording: {}", service, member,
                            e.toString());
                }
            }
            s = new Snapshot(members, clock.instant(), Map.copyOf(links));
            snapshot = s;
            return s;
        }
    }

    private boolean fresh(Snapshot s, Set<UUID> members) {
        return s != null && s.members().equals(members) && clock.instant().isBefore(s.at().plus(REFRESH));
    }

    private static String nameOf(Supplier<String> operation) {
        try {
            return operation == null ? null : operation.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String textOf(Supplier<String> text) {
        try {
            return text == null ? null : text.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static byte[] bytes(String text) {
        return text == null ? null : text.getBytes(StandardCharsets.UTF_8);
    }
}
