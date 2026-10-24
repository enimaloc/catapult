package fr.enimaloc.catapult.service.debug;

import fr.enimaloc.catapult.domain.debug.DebugExchange;
import fr.enimaloc.catapult.domain.debug.DebugExchange.Direction;
import fr.enimaloc.catapult.repository.debug.DebugExchangeRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stores the exchanges made for a member in debug mode. {@link #record} only hands the work to a small, bounded
 * executor of its own (a full queue drops the exchange): redaction, masking of other members, insertion and listener notification all happen there, as
 * the member, and any failure is logged at warn and dropped — the recorded call never sees it.
 */
@Slf4j
@Component
public class DebugExchangeRecorder {

    static final int SERVICE_MAX = 32;
    static final int OPERATION_MAX = 128;
    static final int METHOD_MAX = 16;
    static final int TRACE_ID_MAX = 64;
    /** Few writers so that recording never competes much with request threads for DB connections. */
    static final int WORKERS = 2;
    /** Exchanges waiting beyond this are dropped rather than piling up bodies in memory. */
    static final int QUEUE_CAPACITY = 1000;
    private static final long DROP_WARN_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong lastDropWarn = new AtomicLong(Long.MIN_VALUE);

    private final DebugMemberRegistry registry;
    private final DebugRedactor redactor;
    private final OtherMemberMasker masker;
    private final DebugExchangeRepository repository;
    private final ExternalServices services;
    private final Iterable<DebugExchangeListener> listeners;
    private final TraceIdSource traceIds;
    private final Executor executor;

    @Autowired
    public DebugExchangeRecorder(DebugMemberRegistry registry, DebugRedactor redactor, OtherMemberMasker masker,
                                 DebugExchangeRepository repository, ExternalServices services,
                                 ObjectProvider<DebugExchangeListener> listeners,
                                 ObjectProvider<TraceIdSource> traceIds) {
        this(registry, redactor, masker, repository, services, listeners, () -> {
            TraceIdSource source = traceIds.getIfAvailable();
            return source == null ? null : source.currentTraceId();
        }, boundedExecutor(WORKERS, QUEUE_CAPACITY));
    }

    DebugExchangeRecorder(DebugMemberRegistry registry, DebugRedactor redactor, OtherMemberMasker masker,
                          DebugExchangeRepository repository, ExternalServices services,
                          Iterable<DebugExchangeListener> listeners, TraceIdSource traceIds, Executor executor) {
        this.registry = registry;
        this.redactor = redactor;
        this.masker = masker;
        this.repository = repository;
        this.services = services;
        this.listeners = listeners;
        this.traceIds = traceIds;
        this.executor = executor;
    }

    /**
     * Records {@code raw} for {@code userId} in the background and returns at once. Nothing is stored when the
     * member is no longer in debug mode by the time the work runs.
     *
     * @param operation what the call was for, or null
     * @param filtered  true when {@code raw} is this member's share of a grouped call
     */
    public void record(UUID userId, Direction direction, RawExchange raw, String operation, boolean filtered) {
        if (userId == null || raw == null) {
            return;
        }
        try {
            // Read on the caller's thread: that is where the current trace lives.
            String traceId = currentTraceId();
            executor.execute(() -> DebugContext.runAs(userId,
                    () -> store(userId, direction, raw, operation, filtered, traceId)));
        } catch (RejectedExecutionException e) {
            onDropped();
        } catch (RuntimeException e) {
            log.warn("Could not schedule the debug exchange of {}: {}", userId, e.toString());
        }
    }

    /** Counts a dropped exchange; warns on the first one, then at most once a minute. */
    private void onDropped() {
        long total = dropped.incrementAndGet();
        long now = System.nanoTime();
        long last = lastDropWarn.get();
        if ((last == Long.MIN_VALUE || now - last >= DROP_WARN_INTERVAL_NANOS) && lastDropWarn.compareAndSet(last, now)) {
            log.warn("Debug recording queue full: exchange dropped ({} dropped so far)", total);
        }
    }

    /**
     * {@code workers} virtual threads behind a queue of {@code capacity}: a full queue rejects at once (the caller
     * never waits), and closing it lets the queued exchanges be written.
     */
    static ExecutorService boundedExecutor(int workers, int capacity) {
        return new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), Thread.ofVirtual().name("debug-recorder-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private String currentTraceId() {
        try {
            return cut(traceIds.currentTraceId(), TRACE_ID_MAX);
        } catch (RuntimeException e) {
            log.warn("Could not read the current trace id for a debug exchange: {}", e.toString());
            return null;
        }
    }

    private void store(UUID userId, Direction direction, RawExchange raw, String operation, boolean filtered,
                       String traceId) {
        DebugExchange saved;
        try {
            var mode = registry.modeOf(userId);
            if (mode.isEmpty()) {
                return;
            }
            boolean reveal = mode.get().revealSecrets();
            DebugExchange e = new DebugExchange();
            e.setUserId(userId);
            e.setOccurredAt(raw.at());
            e.setDirection(direction);
            e.setService(cut(services.of(raw.uri()), SERVICE_MAX));
            e.setOperation(cut(operation, OPERATION_MAX));
            e.setMethod(cut(raw.method() == null ? "?" : raw.method(), METHOD_MAX));
            e.setUrl(Objects.requireNonNullElse(mask(redactor.url(raw.uri(), reveal), userId), ""));
            e.setRequestHeaders(mask(redactor.headers(raw.requestHeaders(), reveal), userId));
            e.setRequestBody(mask(redactor.body(raw.requestBody(), contentType(raw.requestHeaders()), reveal), userId));
            e.setStatus(raw.status());
            e.setResponseHeaders(mask(redactor.headers(raw.responseHeaders(), reveal), userId));
            e.setResponseBody(mask(redactor.body(raw.responseBody(), contentType(raw.responseHeaders()), reveal), userId));
            e.setError(mask(redactor.text(raw.error(), reveal), userId));
            e.setDurationMs(raw.durationMs());
            e.setFiltered(filtered);
            e.setTraceId(traceId);
            saved = repository.save(e);
        } catch (RuntimeException ex) {
            log.warn("Could not store the debug exchange of {}: {}", userId, ex.toString());
            return;
        }
        notifyListeners(userId, saved);
    }

    private void notifyListeners(UUID userId, DebugExchange saved) {
        try {
            for (DebugExchangeListener listener : listeners) {
                try {
                    listener.onRecorded(userId, saved);
                } catch (RuntimeException ex) {
                    log.warn("Debug exchange listener {} failed: {}", listener.getClass().getName(), ex.toString());
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Could not look up the debug exchange listeners: {}", ex.toString());
        }
    }

    /** Masks other members; empty text (nothing to record) is stored as null. */
    private String mask(String text, UUID subject) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        return masker.mask(text, subject);
    }

    private static MediaType contentType(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        try {
            return headers.getContentType();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    @PreDestroy
    void close() {
        if (executor instanceof ExecutorService service) {
            service.close();
        }
    }
}
