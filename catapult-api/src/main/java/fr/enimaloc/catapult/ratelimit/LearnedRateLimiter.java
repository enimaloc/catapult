package fr.enimaloc.catapult.ratelimit;

import fr.enimaloc.catapult.common.dto.admin.LearnedRateLimitDto;
import fr.enimaloc.catapult.domain.ratelimit.LearnedRateLimit;
import fr.enimaloc.catapult.ratelimit.LimitEstimator.Estimate;
import fr.enimaloc.catapult.ratelimit.RateLimitScope.Kind;
import fr.enimaloc.catapult.repository.ratelimit.LearnedRateLimitRepository;
import lombok.AccessLevel;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limits the platforms don't document, guessed from their 429s and kept to before calling them: a route counts
 * in its host's budget until it shows a limit of its own. A live call passes while under the limit and gives up
 * after a short wait; bulk work keeps a margin under it and waits its turn. What is learned is stored, shared by the
 * instances, and can be corrected or reset by an admin.
 */
@Slf4j
@Component
public class LearnedRateLimiter {

    /** The header a refusal made here (no call sent) carries: never learned from. */
    public static final String LOCAL_REFUSAL_HEADER = "X-Catapult-Local-Limit";
    private static final long SLICE_MS = 1_000L;
    static final String OTHER_PATH = "/{other}";

    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final LearnedRateLimitProperties props;
    private final LearnedRateLimitRepository repository;
    private final Clock clock;
    private final Map<String, RateLimitScope> scopes = new ConcurrentHashMap<>();

    @Setter(AccessLevel.PACKAGE)
    private Sleeper sleeper = Thread::sleep;

    /** {@code clock}: the application's when there is one (not every profile defines it), else the system's. */
    public LearnedRateLimiter(LearnedRateLimitProperties props, LearnedRateLimitRepository repository,
                              Optional<Clock> clock) {
        this.props = props;
        this.repository = repository;
        this.clock = clock.orElseGet(Clock::systemUTC);
    }

    /** Whether calls to {@code host} are learned from (not one of the hosts left to their documented limits). */
    public boolean learns(String host) {
        if (!props.isEnabled() || host == null || host.isEmpty()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        for (String excluded : props.getExcludedHosts()) {
            String e = excluded.trim().toLowerCase(Locale.ROOT);
            if (e.startsWith("*.") ? h.endsWith(e.substring(1)) : h.equals(e)) return false;
        }
        return true;
    }

    /**
     * Waits until the route may be called, counting the call in. Refuses with a local 429 (carrying
     * {@link #LOCAL_REFUSAL_HEADER}) when a live call would wait longer than allowed, or bulk work was cancelled.
     */
    public void acquire(RouteKey key) {
        if (!learns(key.host())) return;
        RateLimitScope route = route(key);
        RateLimitScope host = host(key.host());
        boolean bulk = RateLimitPriority.current() == RateLimitPriority.BULK;
        Instant deadline = bulk ? null : clock.instant().plus(props.getLiveMaxWait());
        while (true) {
            Instant now = clock.instant();
            RateLimitScope budget = route.kind() == Kind.ROUTE ? route : host;
            boolean[] raised = {false};
            Optional<Instant> retryAt = budget.admit(now, bulk, props.getBulkMargin(), raised);
            if (raised[0]) save(budget);
            if (retryAt.isEmpty()) {
                if (budget != route) route.record(now);
                return;
            }
            Instant at = retryAt.get();
            if (deadline != null && at.isAfter(deadline)) throw refusal(now, at);
            if (bulk && RateLimitPriority.cancelled()) throw refusal(now, at);
            try {
                sleeper.sleep(Math.max(1, Math.min(SLICE_MS, Duration.between(now, at).toMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw refusal(now, at);
            }
        }
    }

    /** What the platform answered a call {@link #acquire}d for: learns from a 429, ends a probe on an answer. */
    public void onResponse(RouteKey key, HttpStatusCode status, HttpHeaders headers) {
        if (!learns(key.host())) return;
        if (headers != null && headers.containsHeader(LOCAL_REFUSAL_HEADER)) return;
        RateLimitScope route = route(key);
        RateLimitScope host = host(key.host());
        Instant now = clock.instant();
        if (status.value() != HttpStatus.TOO_MANY_REQUESTS.value()) {
            host.answered(route.id(), now);
            RateLimitScope budget = route.kind() == Kind.ROUTE ? route : host;
            if (budget.onAnswer(now)) save(budget);
            return;
        }
        Optional<Duration> retryAfter = retryAfter(headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER), now);
        if (route.kind() == Kind.HOST && ownLimitShown(route, host, now) && route.splitOff()) {
            log.info("Learned rate limits: {} has a limit of its own, apart from {}", route.id(), host.id());
        }
        if (route.kind() == Kind.ROUTE) {
            route.on429(now, retryAfter);
        } else {
            route.countRateLimited(now);
            host.on429(now, retryAfter);
            save(host);
        }
        save(route);
        log.warn("Rate limited (429) on {}: left alone until {}", route.id(),
                (route.kind() == Kind.ROUTE ? route : host).blockedUntil(now).orElse(now));
    }

    /**
     * A 429 while the host's budget is well under its learned limit and another of its routes is still answered:
     * the route's limit is its own.
     */
    private boolean ownLimitShown(RateLimitScope route, RateLimitScope host, Instant now) {
        Optional<Estimate> limit = host.limit();
        if (limit.isEmpty()) return false;
        Duration window = limit.get().window();
        return host.used(now) * 2 < limit.get().calls()
                && host.otherRouteAnsweredSince(route.id(), now.minus(window));
    }

    /** Until when calls to this GET route wait after a 429 (its own block, or its host's). */
    public Optional<Instant> blockedUntil(String host, String path) {
        if (!learns(host)) return Optional.empty();
        RateLimitScope route = route(new RouteKey(host.toLowerCase(Locale.ROOT), "GET", path));
        RateLimitScope budget = route.kind() == Kind.ROUTE ? route : host(host.toLowerCase(Locale.ROOT));
        return budget.blockedUntil(clock.instant());
    }

    /** Every scope learned or seen: hosts and their routes, stored ones included, by host then route. */
    public List<LearnedRateLimitDto> list() {
        Instant now = clock.instant();
        Map<String, LearnedRateLimitDto> out = new TreeMap<>();
        try {
            for (LearnedRateLimit e : repository.findAll()) {
                RateLimitScope scope = scopes.computeIfAbsent(e.getId(), id -> fromEntity(e));
                out.put(e.getId(), scope.toDto(now));
            }
        } catch (RuntimeException ex) {
            log.warn("Learned rate limits: the stored ones could not be read: {}", ex.getMessage());
        }
        scopes.values().forEach(s -> out.putIfAbsent(s.id(), s.toDto(now)));
        return out.values().stream()
                .sorted(Comparator.comparing(LearnedRateLimitDto::host)
                        .thenComparing(d -> d.hostScope() ? "" : d.path() + " " + d.method()))
                .toList();
    }

    /**
     * An admin's correction of a scope (404 if unknown): the limit ({@code calls} per {@code windowSeconds}, both or
     * neither), the resume delay, a route's scope; null leaves that part to the learning.
     */
    public LearnedRateLimitDto correct(String id, Integer calls, Integer windowSeconds, Integer resumeSeconds,
                                       String scope) {
        RateLimitScope s = known(id);
        if ((calls == null) != (windowSeconds == null) || (calls != null && (calls < 1 || windowSeconds < 1))
                || (resumeSeconds != null && resumeSeconds < 1)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid correction");
        }
        Kind kind;
        try {
            kind = scope == null || scope.isBlank() ? null : Kind.valueOf(scope.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown scope " + scope);
        }
        s.correct(calls == null ? null : new Estimate(calls, Duration.ofSeconds(windowSeconds)),
                resumeSeconds == null ? null : Duration.ofSeconds(resumeSeconds), kind);
        save(s);
        return s.toDto(clock.instant());
    }

    /** Forgets what was learned and corrected of a scope (404 if unknown). */
    public LearnedRateLimitDto reset(String id) {
        RateLimitScope s = known(id);
        s.reset();
        save(s);
        return s.toDto(clock.instant());
    }

    /** Takes what the other instances learned or an admin corrected meanwhile. */
    @Scheduled(fixedDelayString = "${rate-limit.learning.sync-every:60s}")
    public void sync() {
        try {
            for (LearnedRateLimit e : repository.findAll()) {
                RateLimitScope scope = scopes.get(e.getId());
                if (scope != null) scope.apply(e);
            }
        } catch (RuntimeException ex) {
            log.warn("Learned rate limits: could not sync with the stored ones: {}", ex.getMessage());
        }
    }

    private RateLimitScope known(String id) {
        RateLimitScope s = scopes.get(id);
        if (s != null) return s;
        return repository.findById(id).map(e -> scopes.computeIfAbsent(id, k -> fromEntity(e)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown rate limit " + id));
    }

    private RateLimitScope host(String host) {
        return scope(RateLimitScope.hostId(host), () -> new RateLimitScope(host, null, props, clock.instant()));
    }

    /** The route's scope; past the host's {@code maxRoutesPerHost}, a new route shares {@code /{other}}. */
    private RateLimitScope route(RouteKey key) {
        RouteKey k = key;
        if (!scopes.containsKey(k.id())) {
            String prefix = k.method() + " " + k.host() + "/";
            long known = scopes.keySet().stream().filter(id -> id.startsWith(prefix)).count();
            if (known >= props.getMaxRoutesPerHost()) k = new RouteKey(k.host(), k.method(), OTHER_PATH);
        }
        RouteKey routeKey = k;
        return scope(routeKey.id(), () -> new RateLimitScope(routeKey.host(), routeKey, props, clock.instant()));
    }

    /** The scope, loaded from what was stored the first time it is needed, stored when new. */
    private RateLimitScope scope(String id, java.util.function.Supplier<RateLimitScope> fresh) {
        RateLimitScope existing = scopes.get(id);
        if (existing != null) return existing;
        Optional<LearnedRateLimit> stored;
        try {
            stored = repository.findById(id);
        } catch (RuntimeException ex) {
            log.warn("Learned rate limits: {} could not be read: {}", id, ex.getMessage());
            stored = Optional.empty();
        }
        RateLimitScope created = fresh.get();
        stored.ifPresent(created::apply);
        RateLimitScope winner = scopes.putIfAbsent(id, created);
        if (winner != null) return winner;
        if (stored.isEmpty()) save(created);
        return created;
    }

    private RateLimitScope fromEntity(LearnedRateLimit e) {
        RateLimitScope s = e.getPath() == null
                ? new RateLimitScope(e.getHost(), null, props, clock.instant())
                : new RateLimitScope(e.getHost(), new RouteKey(e.getHost(), e.getMethod(), e.getPath()), props,
                        clock.instant());
        s.apply(e);
        return s;
    }

    private void save(RateLimitScope scope) {
        try {
            repository.save(scope.toEntity(clock.instant()));
        } catch (RuntimeException ex) {
            log.warn("Learned rate limits: {} could not be stored: {}", scope.id(), ex.getMessage());
        }
    }

    private static HttpClientErrorException refusal(Instant now, Instant until) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(LOCAL_REFUSAL_HEADER, "true");
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, Duration.between(now, until).toSeconds())));
        return HttpClientErrorException.create("Learned rate limit reached (no call sent)",
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", headers, new byte[0], StandardCharsets.UTF_8);
    }

    /** A Retry-After header: seconds or an HTTP date. */
    public static Optional<Duration> retryAfter(String header, Instant now) {
        if (header == null || header.isBlank()) return Optional.empty();
        String value = header.trim();
        try {
            return Optional.of(Duration.ofSeconds(Math.max(1, Long.parseLong(value))));
        } catch (NumberFormatException notSeconds) {
            try {
                Instant at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Optional.of(at.isAfter(now) ? Duration.between(now, at) : Duration.ofSeconds(1));
            } catch (DateTimeParseException notADate) {
                return Optional.empty();
            }
        }
    }
}
