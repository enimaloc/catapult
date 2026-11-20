package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.MutableClock;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatWidgetAccessResponse;
import fr.enimaloc.catapult.controller.WidgetDebugLimiter.Admission;
import fr.enimaloc.catapult.controller.WidgetDebugLimiter.Validity;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WidgetDebugLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-20T12:00:00Z"));
    private final UUID widget = UUID.randomUUID();
    /** What catapult-api tells of each token (invalid unless set), and how often it was asked. */
    private final Map<UUID, Validity> validities = new HashMap<>(Map.of(widget, Validity.VALID));
    private final Map<UUID, Integer> asked = new HashMap<>();
    private final WidgetDebugLimiter limiter = new WidgetDebugLimiter(clock, 3, token -> {
        asked.merge(token, 1, Integer::sum);
        return validities.getOrDefault(token, Validity.INVALID);
    });

    @Test
    void aKnownWidgetIsStillLimitedTo10Per10s() {
        for (int i = 0; i < WidgetDebugLimiter.BATCHES; i++) {
            assertThat(limiter.admit(widget)).isEqualTo(Admission.ADMITTED);
            clock.advance(Duration.ofMillis(500));
        }
        assertThat(limiter.admit(widget)).isEqualTo(Admission.REFUSED);

        // The first batch, sent at 12:00:00, leaves the window at 12:00:10
        clock.now = Instant.parse("2026-11-20T12:00:10Z");
        assertThat(limiter.admit(widget)).isEqualTo(Admission.ADMITTED);
        assertThat(limiter.admit(widget)).isEqualTo(Admission.REFUSED);
    }

    @Test
    void aFloodOfRandomTokensDoesNotBlockAValidWidgetsFirstBatch() {
        for (int i = 0; i < 10_000; i++) {
            assertThat(limiter.admit(UUID.randomUUID())).isEqualTo(Admission.UNKNOWN);
        }

        assertThat(limiter.admit(widget)).isEqualTo(Admission.ADMITTED);
    }

    @Test
    void unknownTokensAreNotRelayed() {
        UUID unknown = UUID.randomUUID();

        assertThat(limiter.admit(unknown)).isEqualTo(Admission.UNKNOWN);
        // Remembered for a minute, then asked again
        assertThat(limiter.admit(unknown)).isEqualTo(Admission.UNKNOWN);
        assertThat(asked.get(unknown)).isEqualTo(1);
        clock.advance(WidgetDebugLimiter.INVALID_FOR);
        validities.put(unknown, Validity.VALID);
        assertThat(limiter.admit(unknown)).isEqualTo(Admission.ADMITTED);
    }

    @Test
    void validityIsCached() {
        limiter.admit(widget);
        clock.advance(WidgetDebugLimiter.VALID_FOR.minusSeconds(1));
        limiter.admit(widget);
        assertThat(asked.get(widget)).isEqualTo(1);

        clock.advance(Duration.ofSeconds(1));
        limiter.admit(widget);
        assertThat(asked.get(widget)).isEqualTo(2);
    }

    @Test
    void a404FromTheApiForgetsTheToken() {
        assertThat(limiter.admit(widget)).isEqualTo(Admission.ADMITTED);
        limiter.relayed(widget, 204);
        limiter.relayed(widget, 409);
        assertThat(limiter.admit(widget)).isEqualTo(Admission.ADMITTED);

        validities.put(widget, Validity.INVALID);
        limiter.relayed(widget, 404);
        assertThat(limiter.admit(widget)).isEqualTo(Admission.UNKNOWN);
        assertThat(asked.get(widget)).isEqualTo(1);
    }

    @Test
    void anUnreachableApiIsNotRemembered() {
        UUID unsure = UUID.randomUUID();
        validities.put(unsure, Validity.UNAVAILABLE);

        assertThat(limiter.admit(unsure)).isEqualTo(Admission.UNAVAILABLE);
        validities.put(unsure, Validity.VALID);
        assertThat(limiter.admit(unsure)).isEqualTo(Admission.ADMITTED);
    }

    @Test
    void theRememberedTokensAreBounded_theLeastRecentlySeenLeavingFirst() {
        UUID first = UUID.randomUUID();
        validities.put(first, Validity.VALID);
        limiter.admit(first);
        for (int i = 0; i < 3; i++) {
            UUID other = UUID.randomUUID();
            validities.put(other, Validity.VALID);
            limiter.admit(other);
        }

        limiter.admit(first);
        assertThat(asked.get(first)).isEqualTo(2);
    }

    @Test
    void validityReadsTheWidgetsAccess() {
        assertThat(WidgetDebugLimiter.validity(new ApiClient.Result<>(200, new TwitchatWidgetAccessResponse("o", true))))
                .isEqualTo(Validity.VALID);
        assertThat(WidgetDebugLimiter.validity(new ApiClient.Result<>(200, new TwitchatWidgetAccessResponse("o", false))))
                .isEqualTo(Validity.INVALID);
        assertThat(WidgetDebugLimiter.validity(new ApiClient.Result<>(200, null))).isEqualTo(Validity.INVALID);
        assertThat(WidgetDebugLimiter.validity(new ApiClient.Result<>(404, null))).isEqualTo(Validity.INVALID);
        assertThat(WidgetDebugLimiter.validity(new ApiClient.Result<>(502, null))).isEqualTo(Validity.UNAVAILABLE);
        assertThat(WidgetDebugLimiter.validity(null)).isEqualTo(Validity.UNAVAILABLE);
    }
}
