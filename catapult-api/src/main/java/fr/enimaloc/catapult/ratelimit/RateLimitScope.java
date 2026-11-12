package fr.enimaloc.catapult.ratelimit;

import fr.enimaloc.catapult.common.dto.admin.LearnedRateLimitDto;
import fr.enimaloc.catapult.domain.ratelimit.LearnedRateLimit;
import fr.enimaloc.catapult.ratelimit.LimitEstimator.Estimate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One scope's learning and state, guarded by itself: a host (the budget its {@link Kind#HOST} routes share) or a
 * route. Only the scope a call is counted in (its route when {@link Kind#ROUTE}, else its host) blocks and learns;
 * a route in its host's budget keeps its own 429 count and calls for the admin tab.
 */
final class RateLimitScope {

    /** A route's budget: its host's, or its own. */
    enum Kind { HOST, ROUTE }

    private final String id;
    private final String host;
    private final RouteKey route;
    private final LimitEstimator estimator;
    private final ResumeDelay resume;
    private Kind kind = Kind.HOST;
    private boolean kindPinned;
    private Estimate overrideLimit;
    private Duration overrideResume;
    private Instant blockedUntil;
    /** Blocked after a 429: the next answer tells whether the wait was long enough. */
    private boolean probing;
    /** The block's wait was the learned one (no Retry-After): its outcome teaches the resume delay. */
    private boolean learningResume;
    private int rateLimitedCount;
    private Instant lastRateLimitedAt;
    /** The last admin correction or reset taken: a newer one stored is taken before writing over it. */
    private Instant adminSeenAt;
    /** A host's: when each of its routes was last answered (no 429), by route ID. */
    private final Map<String, Instant> lastAnswerByRoute = new ConcurrentHashMap<>();

    RateLimitScope(String host, RouteKey route, LearnedRateLimitProperties props, Instant now) {
        this.host = host;
        this.route = route;
        this.id = route == null ? hostId(host) : route.id();
        this.estimator = new LimitEstimator(props.getRaiseAfter(), now);
        this.resume = new ResumeDelay(props.getForgetFailAfter());
    }

    static String hostId(String host) {
        return "HOST " + host;
    }

    String id() {
        return id;
    }

    synchronized Kind kind() {
        return kind;
    }

    /** The limit that applies: the admin's correction, else the one learned. */
    synchronized Optional<Estimate> limit() {
        return overrideLimit != null ? Optional.of(overrideLimit) : estimator.estimate();
    }

    synchronized Duration resumeDelay() {
        return overrideResume != null ? overrideResume : resume.current();
    }

    synchronized Optional<Instant> blockedUntil(Instant now) {
        return blockedUntil != null && blockedUntil.isAfter(now) ? Optional.of(blockedUntil) : Optional.empty();
    }

    /**
     * Counts a call in if the scope lets it go now ({@code bulk}: under {@code margin} of the limit); else when to
     * try again. Returns whether the learned limit was raised, through {@code raised}.
     */
    synchronized Optional<Instant> admit(Instant now, boolean bulk, double margin, boolean[] raised) {
        if (blockedUntil != null && blockedUntil.isAfter(now)) return Optional.of(blockedUntil);
        if (overrideLimit == null) {
            Optional<Estimate> before = estimator.estimate();
            estimator.maybeRaise(now);
            raised[0] = !before.equals(estimator.estimate());
        }
        Optional<Estimate> limit = limit();
        if (limit.isPresent()) {
            Estimate l = limit.get();
            int cap = bulk ? Math.max(1, (int) Math.floor(l.calls() * margin)) : l.calls();
            if (estimator.countWithin(l.window(), now) >= cap) {
                Instant freed = estimator.oldestWithin(l.window(), now).map(at -> at.plus(l.window())).orElse(now);
                return Optional.of(freed.isAfter(now) ? freed : now.plusMillis(10));
            }
        }
        estimator.record(now);
        return Optional.empty();
    }

    /** Counts a call made through another scope's budget (a route in its host's). */
    synchronized void record(Instant now) {
        estimator.record(now);
    }

    /** The calls counted here in the limit's window (a minute without a limit). */
    synchronized int used(Instant now) {
        return estimator.countWithin(limit().map(Estimate::window).orElse(Duration.ofMinutes(1)), now);
    }

    /**
     * A 429 for this budget: blocked for {@code retryAfter} when the platform says, else for the learned delay (made
     * longer first when the last one wasn't enough); the limit tightened from the calls before it, unless the 429
     * answers the first call after a wait (the wait was too short, not the limit too high).
     */
    synchronized void on429(Instant now, Optional<Duration> retryAfter) {
        countRateLimited(now);
        boolean afterWait = probing;
        Duration wait;
        if (retryAfter.isPresent()) {
            wait = retryAfter.get();
            learningResume = false;
        } else {
            if (probing && learningResume && overrideResume == null) resume.onFailure(now);
            wait = resumeDelay();
            learningResume = true;
        }
        if (overrideLimit == null && !afterWait) estimator.on429(now, wait);
        Instant until = now.plus(wait);
        if (blockedUntil == null || until.isAfter(blockedUntil)) blockedUntil = until;
        // When the wait ends the platform counts afresh: the calls before it no longer fill the window
        estimator.forgetCalls(blockedUntil);
        probing = true;
    }

    /** A route in its host's budget got a 429: counted for the admin tab only. */
    synchronized void countRateLimited(Instant now) {
        rateLimitedCount++;
        lastRateLimitedAt = now;
    }

    /** An answer that is no 429: ends a probe, teaching the resume delay. Returns whether anything was learned. */
    synchronized boolean onAnswer(Instant now) {
        if (!probing) return false;
        if (learningResume && overrideResume == null) resume.onSuccess(now);
        probing = false;
        learningResume = false;
        return true;
    }

    void answered(String routeId, Instant now) {
        lastAnswerByRoute.put(routeId, now);
    }

    /** Whether a route of this host other than {@code routeId} was answered since {@code since}. */
    boolean otherRouteAnsweredSince(String routeId, Instant since) {
        return lastAnswerByRoute.entrySet().stream()
                .anyMatch(e -> !e.getKey().equals(routeId) && !e.getValue().isBefore(since));
    }

    /** The learning splits the route off its host's budget; never while an admin chose its scope. */
    synchronized boolean splitOff() {
        if (kindPinned || kind == Kind.ROUTE) return false;
        kind = Kind.ROUTE;
        return true;
    }

    /**
     * An admin's correction: null values leave that part to the learning; a route's scope chosen ({@code pinnedKind}),
     * or given back to the learning ({@code unpin}), else left as it is.
     */
    synchronized void correct(Estimate limit, Duration resumeDelay, Kind pinnedKind, boolean unpin, Instant now) {
        overrideLimit = limit;
        overrideResume = resumeDelay;
        if (route != null && pinnedKind != null) {
            kind = pinnedKind;
            kindPinned = true;
        } else if (route != null && unpin) {
            kindPinned = false;
        }
        adminSeenAt = now;
    }

    /** Whether {@code stored} carries an admin's correction or reset this scope hasn't taken yet. */
    synchronized boolean behind(LearnedRateLimit stored) {
        Instant changed = stored.getAdminChangedAt();
        return changed != null && (adminSeenAt == null || changed.isAfter(adminSeenAt));
    }

    /** Forgets everything learned and corrected: the scope starts again without a known limit. */
    synchronized void reset(Instant now) {
        adminSeenAt = now;
        estimator.reset();
        resume.reset();
        overrideLimit = null;
        overrideResume = null;
        blockedUntil = null;
        probing = false;
        learningResume = false;
        rateLimitedCount = 0;
        lastRateLimitedAt = null;
        if (route != null) {
            kind = Kind.HOST;
            kindPinned = false;
        }
    }

    synchronized LearnedRateLimit toEntity(Instant now) {
        Optional<Estimate> learned = estimator.estimate();
        ResumeDelay.State r = resume.state();
        return LearnedRateLimit.builder()
                .id(id).host(host)
                .method(route == null ? null : route.method())
                .path(route == null ? null : route.path())
                .scope(route == null ? null : kind.name())
                .scopePinned(kindPinned)
                .limitCalls(learned.map(Estimate::calls).orElse(null))
                .limitWindowSeconds(learned.map(e -> (int) e.window().toSeconds()).orElse(null))
                .resumeMs(r.current().toMillis())
                .resumeFailMs(r.fail() == null ? null : r.fail().toMillis())
                .resumeOkMs(r.ok() == null ? null : r.ok().toMillis())
                .lastFailAt(r.lastFail())
                .rateLimitedCount(rateLimitedCount)
                .lastRateLimitedAt(lastRateLimitedAt)
                .overrideCalls(overrideLimit == null ? null : overrideLimit.calls())
                .overrideWindowSeconds(overrideLimit == null ? null : (int) overrideLimit.window().toSeconds())
                .overrideResumeMs(overrideResume == null ? null : overrideResume.toMillis())
                .adminChangedAt(adminSeenAt)
                .updatedAt(now)
                .build();
    }

    /** Takes what was stored (learned, corrected, the scope), keeping the calls counted here. */
    synchronized void apply(LearnedRateLimit e) {
        estimator.set(e.getLimitCalls() == null || e.getLimitWindowSeconds() == null ? null
                : new Estimate(e.getLimitCalls(), Duration.ofSeconds(e.getLimitWindowSeconds())), e.getLastRateLimitedAt());
        resume.restore(new ResumeDelay.State(Duration.ofMillis(e.getResumeMs()),
                e.getResumeFailMs() == null ? null : Duration.ofMillis(e.getResumeFailMs()),
                e.getResumeOkMs() == null ? null : Duration.ofMillis(e.getResumeOkMs()), e.getLastFailAt()));
        overrideLimit = e.getOverrideCalls() == null || e.getOverrideWindowSeconds() == null ? null
                : new Estimate(e.getOverrideCalls(), Duration.ofSeconds(e.getOverrideWindowSeconds()));
        overrideResume = e.getOverrideResumeMs() == null ? null : Duration.ofMillis(e.getOverrideResumeMs());
        rateLimitedCount = e.getRateLimitedCount();
        lastRateLimitedAt = e.getLastRateLimitedAt();
        if (route != null && e.getScope() != null) kind = Kind.valueOf(e.getScope());
        kindPinned = e.isScopePinned();
        adminSeenAt = e.getAdminChangedAt();
    }

    synchronized LearnedRateLimitDto toDto(Instant now) {
        Optional<Estimate> limit = limit();
        return new LearnedRateLimitDto(id, host, route == null ? null : route.method(),
                route == null ? null : route.path(), route == null ? null : kind.name(), kindPinned,
                limit.map(Estimate::calls).orElse(null), limit.map(l -> (int) l.window().toSeconds()).orElse(null),
                overrideLimit != null, resumeDelay().toMillis(), overrideResume != null, rateLimitedCount,
                lastRateLimitedAt, used(now), blockedUntil(now).orElse(null));
    }
}
