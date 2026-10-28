package fr.enimaloc.catapult.service.debug;

import fr.enimaloc.catapult.common.dto.MyDebugDto;
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
    private static final MyDebugDto ON = new MyDebugDto(true, 1);
    private static final MyDebugDto OFF = new MyDebugDto(false, 0);

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
        when(api.myDebug()).thenReturn(ON);

        assertThat(debug.isActive(MEMBER)).isTrue();
        clock.advance(Duration.ofSeconds(29));
        assertThat(debug.isActive(MEMBER)).isTrue();
        verify(api, times(1)).myDebug();

        when(api.myDebug()).thenReturn(OFF);
        clock.advance(Duration.ofSeconds(2));
        assertThat(debug.isActive(MEMBER)).isFalse();
        verify(api, times(2)).myDebug();
    }

    @Test
    void evict_makesTheNextAskReadItAgain() {
        when(api.myDebug()).thenReturn(OFF);
        assertThat(debug.isActive(MEMBER)).isFalse();

        when(api.myDebug()).thenReturn(ON);
        debug.evict(MEMBER);

        assertThat(debug.isActive(MEMBER)).isTrue();
    }

    @Test
    void aFailure_isNotDebugged_andIsRememberedBriefly() {
        when(api.myDebug()).thenThrow(new IllegalStateException("down"));

        assertThat(debug.isActive(MEMBER)).isFalse();
        assertThat(debug.isActive(MEMBER)).isFalse();
        verify(api, times(1)).myDebug();

        org.mockito.Mockito.doReturn(ON).when(api).myDebug();
        clock.advance(Duration.ofSeconds(6));
        assertThat(debug.isActive(MEMBER)).isTrue();
    }

    @Test
    void anApiThatCouldNotTell_returnsNull_isNotDebugged_andIsRememberedOnlyBriefly() {
        when(api.myDebug()).thenReturn(null);

        assertThat(debug.isActive(MEMBER)).isFalse();
        clock.advance(Duration.ofSeconds(4));
        assertThat(debug.isActive(MEMBER)).isFalse();
        verify(api, times(1)).myDebug();

        when(api.myDebug()).thenReturn(ON);
        clock.advance(Duration.ofSeconds(2));
        assertThat(debug.isActive(MEMBER)).isTrue();
    }

    @Test
    void nobody_isNotDebugged() {
        assertThat(debug.isActive(null)).isFalse();
        assertThat(debug.replayRate(null)).isEmpty();
    }

    @Test
    void replayRate_isTheDebuggedMembersOwn_fromTheSameRememberedAnswer() {
        when(api.myDebug()).thenReturn(new MyDebugDto(true, 0.25));

        assertThat(debug.replayRate(MEMBER)).hasValue(0.25);
        assertThat(debug.isActive(MEMBER)).isTrue();
        verify(api, times(1)).myDebug();
    }

    @Test
    void replayRate_isEmpty_whenNotDebuggedOrOnFailure() {
        when(api.myDebug()).thenReturn(OFF);
        assertThat(debug.replayRate(MEMBER)).isEmpty();

        debug.evict(MEMBER);
        when(api.myDebug()).thenThrow(new IllegalStateException("down"));
        assertThat(debug.replayRate(MEMBER)).isEmpty();
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

    private static org.springframework.mock.web.MockHttpServletRequest sessionRequest() {
        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
        request.getSession(true);
        return request;
    }

    @Test
    void withoutAnApiService_nobodyIsDebugged() {
        DebugMembers none = new DebugMembers(new StaticApplicationContext().getBeanProvider(ApiService.class), clock);

        assertThat(none.isActive(MEMBER)).isFalse();
        assertThat(none.isCurrentActive()).isFalse();
    }

    @Test
    void isCurrentActive_asksAboutTheMemberOfTheRequest_andIsFalseWithoutOne() {
        when(api.currentUserId()).thenReturn(MEMBER);
        when(api.myDebug()).thenReturn(ON);
        assertThat(debug.isCurrentActive()).isTrue();

        when(api.currentUserId()).thenReturn(null);
        assertThat(debug.isCurrentActive()).isFalse();

        when(api.currentUserId()).thenThrow(new IllegalStateException("no request"));
        assertThat(debug.isCurrentActive()).isFalse();
    }

    @Test
    void isActiveOn_readsTheSessionsMember_andRestoresWhatWasBound() {
        when(api.currentUserId()).thenReturn(MEMBER);
        when(api.myDebug()).thenReturn(ON);
        org.springframework.web.context.request.RequestAttributes before =
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();

        assertThat(debug.isActiveOn(sessionRequest())).isTrue();

        assertThat(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()).isSameAs(before);
    }

    @Test
    void isActiveOn_isFalseWithoutARequestOrASession_andWithoutAskingTheApi() {
        assertThat(debug.isActiveOn(null)).isFalse();
        assertThat(debug.isActiveOn(new org.springframework.mock.web.MockHttpServletRequest())).isFalse();

        verify(api, org.mockito.Mockito.never()).myDebug();
    }

    @Test
    void isActiveOn_isFalseWhenTheRequestCannotBeRead() {
        jakarta.servlet.http.HttpServletRequest broken = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(broken.getSession(false)).thenThrow(new IllegalStateException("recycled"));

        assertThat(debug.isActiveOn(broken)).isFalse();
    }
}
