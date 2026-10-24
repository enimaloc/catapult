package fr.enimaloc.catapult.service.debug;

import fr.enimaloc.catapult.service.ApiService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.StaticApplicationContext;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DebugMembersTest {
    private static final UUID MEMBER = UUID.randomUUID();

    private final ApiService api = mock(ApiService.class);
    private final MutableClock clock = new MutableClock();
    private final DebugMembers debug = new DebugMembers(provider(api), clock);

    private static ObjectProvider<ApiService> provider(ApiService api) {
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("api", api);
        return context.getBeanProvider(ApiService.class);
    }

    @Test
    void theAnswerIsRemembered_for30Seconds() {
        when(api.myDebugActive()).thenReturn(true);

        assertThat(debug.isActive(MEMBER)).isTrue();
        clock.advance(Duration.ofSeconds(29));
        assertThat(debug.isActive(MEMBER)).isTrue();
        verify(api, times(1)).myDebugActive();

        when(api.myDebugActive()).thenReturn(false);
        clock.advance(Duration.ofSeconds(2));
        assertThat(debug.isActive(MEMBER)).isFalse();
        verify(api, times(2)).myDebugActive();
    }

    @Test
    void evict_makesTheNextAskReadItAgain() {
        when(api.myDebugActive()).thenReturn(false);
        assertThat(debug.isActive(MEMBER)).isFalse();

        when(api.myDebugActive()).thenReturn(true);
        debug.evict(MEMBER);

        assertThat(debug.isActive(MEMBER)).isTrue();
    }

    @Test
    void aFailure_isNotDebugged_andIsRememberedBriefly() {
        when(api.myDebugActive()).thenThrow(new IllegalStateException("down"));

        assertThat(debug.isActive(MEMBER)).isFalse();
        assertThat(debug.isActive(MEMBER)).isFalse();
        verify(api, times(1)).myDebugActive();

        org.mockito.Mockito.doReturn(true).when(api).myDebugActive();
        clock.advance(Duration.ofSeconds(6));
        assertThat(debug.isActive(MEMBER)).isTrue();
    }

    @Test
    void nobody_isNotDebugged() {
        assertThat(debug.isActive(null)).isFalse();
    }

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-24T10:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
