package fr.enimaloc.catapult.ratelimit;

import fr.enimaloc.catapult.common.dto.admin.LearnedRateLimitDto;
import fr.enimaloc.catapult.domain.ratelimit.LearnedRateLimit;
import fr.enimaloc.catapult.repository.ratelimit.LearnedRateLimitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LearnedRateLimiterTest {

    private static final String STORE = "store.steampowered.com";
    private static final RouteKey APPDETAILS = new RouteKey(STORE, "GET", "/api/appdetails");
    private static final RouteKey PAGE = new RouteKey(STORE, "GET", "/app/{id}");

    /** A clock the test (and the limiter's waits) move. */
    static final class MovingClock extends Clock {
        Instant now = Instant.parse("2026-11-12T10:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    private final MovingClock clock = new MovingClock();
    private final LearnedRateLimitProperties props = new LearnedRateLimitProperties();
    private final Map<String, LearnedRateLimit> stored = new HashMap<>();
    private LearnedRateLimitRepository repository;
    private LearnedRateLimiter limiter;
    private final AtomicInteger slept = new AtomicInteger();

    @BeforeEach
    void setUp() {
        repository = mock(LearnedRateLimitRepository.class);
        when(repository.findById(anyString())).thenAnswer(i -> Optional.ofNullable(stored.get(i.<String>getArgument(0))));
        when(repository.findAll()).thenAnswer(i -> new ArrayList<>(stored.values()));
        when(repository.save(any())).thenAnswer(i -> {
            LearnedRateLimit e = i.getArgument(0);
            stored.put(e.getId(), e);
            return e;
        });
        limiter = new LearnedRateLimiter(props, repository, clock);
        limiter.setSleeper(ms -> {
            slept.addAndGet((int) ms);
            clock.advance(Duration.ofMillis(ms));
        });
    }

    private void calls(RouteKey key, int n) {
        for (int i = 0; i < n; i++) {
            limiter.acquire(key);
            limiter.onResponse(key, HttpStatus.OK, new HttpHeaders());
            clock.advance(Duration.ofSeconds(1));
        }
    }

    private void tooMany(RouteKey key, String retryAfter) {
        limiter.acquire(key);
        HttpHeaders h = new HttpHeaders();
        if (retryAfter != null) h.set(HttpHeaders.RETRY_AFTER, retryAfter);
        limiter.onResponse(key, HttpStatus.TOO_MANY_REQUESTS, h);
    }

    /** The store's scopes known for an hour: a 429 then tells a limit. */
    private void known() {
        limiter.blockedUntil(STORE, "/api/appdetails");
        limiter.blockedUntil(STORE, "/app/{id}");
        clock.advance(Duration.ofHours(1));
    }

    private LearnedRateLimitDto row(String id) {
        return limiter.list().stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void documentedHosts_areLeftAlone() {
        assertThat(limiter.learns("api.steampowered.com")).isFalse();
        assertThat(limiter.learns("eu.api.blizzard.com")).isFalse();
        assertThat(limiter.learns("")).isFalse();
        assertThat(limiter.learns(null)).isFalse();
        assertThat(limiter.learns(STORE)).isTrue();

        RouteKey twitch = new RouteKey("api.twitch.tv", "GET", "/helix/users");
        limiter.acquire(twitch);
        limiter.onResponse(twitch, HttpStatus.TOO_MANY_REQUESTS, new HttpHeaders());
        assertThat(stored).isEmpty();
        assertThat(limiter.blockedUntil("api.twitch.tv", "/helix/users")).isEmpty();

        props.setEnabled(false);
        assertThat(limiter.learns(STORE)).isFalse();
    }

    @Test
    void a429_blocksTheWholeHost_andALiveCallElsewhereIsRefusedWithoutWaiting() {
        known();
        calls(APPDETAILS, 20);
        tooMany(APPDETAILS, null);

        assertThat(limiter.blockedUntil(STORE, "/api/appdetails")).contains(clock.now.plusSeconds(60));
        assertThat(limiter.blockedUntil(STORE, "/app/{id}")).contains(clock.now.plusSeconds(60));
        assertThatThrownBy(() -> limiter.acquire(PAGE))
                .isInstanceOfSatisfying(HttpClientErrorException.TooManyRequests.class, e -> {
                    assertThat(e.getResponseHeaders().getFirst(LearnedRateLimiter.LOCAL_REFUSAL_HEADER)).isEqualTo("true");
                    assertThat(e.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
                });
        assertThat(slept.get()).isZero();

        LearnedRateLimitDto host = row("HOST " + STORE);
        assertThat(host.limitCalls()).isEqualTo(20);
        assertThat(host.limitWindowSeconds()).isEqualTo(60);
        assertThat(host.rateLimitedCount()).isEqualTo(1);
        assertThat(row(APPDETAILS.id()).rateLimitedCount()).isEqualTo(1);
        assertThat(row(APPDETAILS.id()).scope()).isEqualTo("HOST");
    }

    @Test
    void aLocalRefusal_isNeverLearned() {
        HttpHeaders local = new HttpHeaders();
        local.set(LearnedRateLimiter.LOCAL_REFUSAL_HEADER, "true");
        limiter.onResponse(APPDETAILS, HttpStatus.TOO_MANY_REQUESTS, local);
        assertThat(stored).isEmpty();
    }

    @Test
    void theRetryAfter_isFollowed_andTeachesNoResumeDelay() {
        known();
        calls(APPDETAILS, 5);
        tooMany(APPDETAILS, "7");
        assertThat(limiter.blockedUntil(STORE, "/api/appdetails")).contains(clock.now.plusSeconds(7));
        assertThat(row("HOST " + STORE).limitWindowSeconds()).isEqualTo(10);

        clock.advance(Duration.ofSeconds(8));
        calls(APPDETAILS, 1);
        assertThat(row("HOST " + STORE).resumeMs()).isEqualTo(60_000);
    }

    @Test
    void theResumeDelay_isLearnedFromTheFirstCallAfterEachWait() {
        calls(APPDETAILS, 3);
        tooMany(APPDETAILS, null);
        clock.advance(Duration.ofSeconds(60));
        calls(APPDETAILS, 1);
        assertThat(row("HOST " + STORE).resumeMs()).isEqualTo(48_000);

        clock.advance(Duration.ofMinutes(5));
        tooMany(APPDETAILS, null);
        clock.advance(Duration.ofSeconds(48));
        tooMany(APPDETAILS, null);
        assertThat(row("HOST " + STORE).resumeMs()).isEqualTo(96_000);
        assertThat(limiter.blockedUntil(STORE, "/api/appdetails")).contains(clock.now.plusSeconds(96));
    }

    @Test
    void bulkWork_keepsAMarginForLiveCalls_andWaitsItsTurn() {
        assertThat(limiter.blockedUntil(STORE, "/api/appdetails")).isEmpty();
        limiter.correct("HOST " + STORE, 10, 60, null, null);
        RateLimitPriority.run(RateLimitPriority.BULK, () -> false, () -> {
            for (int i = 0; i < 8; i++) limiter.acquire(APPDETAILS);
        });
        assertThat(slept.get()).isZero();

        limiter.acquire(APPDETAILS);
        limiter.acquire(APPDETAILS);
        assertThatThrownBy(() -> limiter.acquire(PAGE)).isInstanceOf(HttpClientErrorException.TooManyRequests.class);

        RateLimitPriority.run(RateLimitPriority.BULK, () -> false, () -> limiter.acquire(APPDETAILS));
        assertThat(Duration.ofMillis(slept.get())).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void cancelledBulkWork_stopsWaiting() {
        calls(APPDETAILS, 2);
        tooMany(APPDETAILS, "600");
        AtomicInteger checks = new AtomicInteger();

        assertThatThrownBy(() -> RateLimitPriority.run(RateLimitPriority.BULK, () -> checks.incrementAndGet() > 2,
                () -> limiter.acquire(APPDETAILS))).isInstanceOf(HttpClientErrorException.TooManyRequests.class);
        assertThat(slept.get()).isEqualTo(2_000);
    }

    @Test
    void anInterruptedWait_isRefused() {
        calls(APPDETAILS, 2);
        tooMany(APPDETAILS, "600");
        limiter.setSleeper(ms -> { throw new InterruptedException(); });

        assertThatThrownBy(() -> RateLimitPriority.run(RateLimitPriority.BULK, () -> false,
                () -> limiter.acquire(APPDETAILS))).isInstanceOf(HttpClientErrorException.TooManyRequests.class);
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void aCorrection_isKeptByTheLearning_untilReset() {
        limiter.acquire(APPDETAILS);
        limiter.correct("HOST " + STORE, 50, 60, 30, null);
        calls(APPDETAILS, 10);
        tooMany(APPDETAILS, null);

        LearnedRateLimitDto host = row("HOST " + STORE);
        assertThat(host.limitCalls()).isEqualTo(50);
        assertThat(host.limitCorrected()).isTrue();
        assertThat(host.resumeMs()).isEqualTo(30_000);
        assertThat(limiter.blockedUntil(STORE, "/api/appdetails")).contains(clock.now.plusSeconds(30));

        LearnedRateLimitDto reset = limiter.reset("HOST " + STORE);
        assertThat(reset.limitCalls()).isNull();
        assertThat(reset.limitCorrected()).isFalse();
        assertThat(reset.rateLimitedCount()).isZero();
        assertThat(reset.resumeMs()).isEqualTo(60_000);
        assertThat(limiter.blockedUntil(STORE, "/api/appdetails")).isEmpty();
    }

    @Test
    void aRouteWithALimitOfItsOwn_isSplitOffItsHost() {
        known();
        calls(APPDETAILS, 30);
        tooMany(APPDETAILS, null);
        clock.advance(Duration.ofMinutes(10));
        calls(PAGE, 2);
        calls(APPDETAILS, 2);

        tooMany(APPDETAILS, null);

        assertThat(row(APPDETAILS.id()).scope()).isEqualTo("ROUTE");
        assertThat(limiter.blockedUntil(STORE, "/api/appdetails")).isPresent();
        assertThat(limiter.blockedUntil(STORE, "/app/{id}")).isEmpty();
        limiter.acquire(PAGE);
    }

    @Test
    void anAdminsScope_isNeverChangedByTheLearning() {
        limiter.acquire(APPDETAILS);
        limiter.correct(APPDETAILS.id(), null, null, null, "route");
        assertThat(row(APPDETAILS.id()).scopePinned()).isTrue();
        tooMany(APPDETAILS, null);
        assertThat(limiter.blockedUntil(STORE, "/app/{id}")).isEmpty();

        limiter.correct(APPDETAILS.id(), null, null, null, "HOST");
        calls(PAGE, 30);
        clock.advance(Duration.ofMinutes(5));
        tooMany(APPDETAILS, null);
        assertThat(row(APPDETAILS.id()).scope()).isEqualTo("HOST");

        limiter.reset(APPDETAILS.id());
        assertThat(row(APPDETAILS.id()).scopePinned()).isFalse();
    }

    @Test
    void aBadCorrection_isRefused() {
        limiter.acquire(APPDETAILS);
        String id = "HOST " + STORE;
        assertThatThrownBy(() -> limiter.correct(id, 5, null, null, null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> limiter.correct(id, 0, 60, null, null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> limiter.correct(id, 5, 0, null, null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> limiter.correct(id, null, null, 0, null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> limiter.correct(id, null, null, null, "nope")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> limiter.reset("HOST unknown")).isInstanceOf(ResponseStatusException.class);
        assertThat(limiter.correct(id, null, null, null, " ").limitCorrected()).isFalse();
    }

    @Test
    void whatWasStored_isTakenBack_andSyncedFromTheOtherInstances() {
        known();
        calls(APPDETAILS, 10);
        tooMany(APPDETAILS, null);
        LearnedRateLimiter other = new LearnedRateLimiter(props, repository, clock);

        assertThat(other.blockedUntil(STORE, "/api/appdetails")).isEmpty();
        assertThat(other.list()).extracting(LearnedRateLimitDto::id)
                .containsExactly("HOST " + STORE, APPDETAILS.id(), PAGE.id());
        assertThat(other.list().getFirst().limitCalls()).isEqualTo(10);
        assertThat(other.list().getFirst().hostScope()).isTrue();

        other.correct(APPDETAILS.id(), 3, 10, 5, "ROUTE");
        limiter.sync();
        assertThat(row(APPDETAILS.id()).limitCalls()).isEqualTo(3);
        assertThat(row(APPDETAILS.id()).scope()).isEqualTo("ROUTE");
    }

    @Test
    void aFailingStore_neverFailsTheCalls() {
        doThrow(new IllegalStateException("db down")).when(repository).findById(anyString());
        doThrow(new IllegalStateException("db down")).when(repository).save(any());
        doThrow(new IllegalStateException("db down")).when(repository).findAll();

        calls(APPDETAILS, 2);
        tooMany(APPDETAILS, null);
        limiter.sync();

        assertThat(limiter.list()).hasSize(2);
        assertThat(limiter.blockedUntil(STORE, "/api/appdetails")).isPresent();
    }

    @Test
    void longNearTheLimit_raisesIt() {
        known();
        limiter.acquire(APPDETAILS);
        tooMany(APPDETAILS, null);
        assertThat(row("HOST " + STORE).limitCalls()).isEqualTo(1);
        clock.advance(Duration.ofHours(2));
        calls(APPDETAILS, 1);
        calls(APPDETAILS, 1);
        assertThat(row("HOST " + STORE).limitCalls()).isEqualTo(2);
        verify(repository, atLeast(3)).save(any());
    }

    @Test
    void pastTheRoutesAHostMayHave_newOnesShareOne() {
        props.setMaxRoutesPerHost(2);
        limiter.acquire(APPDETAILS);
        limiter.acquire(PAGE);
        limiter.acquire(new RouteKey(STORE, "GET", "/a"));
        limiter.acquire(new RouteKey(STORE, "GET", "/b"));
        limiter.acquire(APPDETAILS);

        assertThat(limiter.list()).extracting(LearnedRateLimitDto::path)
                .containsExactly(null, "/api/appdetails", "/app/{id}", LearnedRateLimiter.OTHER_PATH);
    }

    @Test
    void retryAfter_readsSecondsOrADate() {
        Instant now = Instant.parse("2026-11-12T10:00:00Z");
        assertThat(LearnedRateLimiter.retryAfter("30", now)).contains(Duration.ofSeconds(30));
        assertThat(LearnedRateLimiter.retryAfter("0", now)).contains(Duration.ofSeconds(1));
        assertThat(LearnedRateLimiter.retryAfter("Thu, 12 Nov 2026 10:01:00 GMT", now)).contains(Duration.ofMinutes(1));
        assertThat(LearnedRateLimiter.retryAfter("Thu, 12 Nov 2026 09:00:00 GMT", now)).contains(Duration.ofSeconds(1));
        assertThat(LearnedRateLimiter.retryAfter("soon", now)).isEmpty();
        assertThat(LearnedRateLimiter.retryAfter(" ", now)).isEmpty();
        assertThat(LearnedRateLimiter.retryAfter(null, now)).isEmpty();
    }
}
