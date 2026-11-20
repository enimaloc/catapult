package fr.enimaloc.catapult.service.dryrun;

import fr.enimaloc.catapult.common.dto.DryRunStateDto;
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

class DryRunMembersTest {
    private static final UUID MEMBER = UUID.randomUUID();
    private static final DryRunStateDto ON = new DryRunStateDto(true, Instant.parse("2026-11-20T09:00:00Z"));
    private static final DryRunStateDto OFF = new DryRunStateDto(false, null);

    private final ApiService api = mock(ApiService.class);
    private final MutableClock clock = new MutableClock();
    private final DryRunMembers dryRun = new DryRunMembers(provider(api), clock);

    private static ObjectProvider<ApiService> provider(ApiService api) {
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("api", api);
        return context.getBeanProvider(ApiService.class);
    }

    @Test
    void theAnswerIsRemembered_for30Seconds() {
        when(api.currentUserId()).thenReturn(MEMBER);
        when(api.myDryRun()).thenReturn(ON);

        assertThat(dryRun.isCurrentActive()).isTrue();
        clock.advance(Duration.ofSeconds(29));
        assertThat(dryRun.isCurrentActive()).isTrue();
        verify(api, times(1)).myDryRun();

        when(api.myDryRun()).thenReturn(OFF);
        clock.advance(Duration.ofSeconds(2));
        assertThat(dryRun.isCurrentActive()).isFalse();
        verify(api, times(2)).myDryRun();
    }

    @Test
    void evict_makesTheNextAskReadItAgain() {
        when(api.currentUserId()).thenReturn(MEMBER);
        when(api.myDryRun()).thenReturn(OFF);
        assertThat(dryRun.isCurrentActive()).isFalse();

        when(api.myDryRun()).thenReturn(ON);
        dryRun.evict(MEMBER);

        assertThat(dryRun.isCurrentActive()).isTrue();
    }

    @Test
    void aFailureOrAnApiThatCouldNotTell_isNotInDryRun_andIsRememberedBriefly() {
        when(api.currentUserId()).thenReturn(MEMBER);
        when(api.myDryRun()).thenThrow(new IllegalStateException("down"));

        assertThat(dryRun.isCurrentActive()).isFalse();
        assertThat(dryRun.isCurrentActive()).isFalse();
        verify(api, times(1)).myDryRun();

        org.mockito.Mockito.doReturn(null).when(api).myDryRun();
        clock.advance(Duration.ofSeconds(6));
        assertThat(dryRun.isCurrentActive()).isFalse();
        clock.advance(Duration.ofSeconds(4));
        assertThat(dryRun.isCurrentActive()).isFalse();
        verify(api, times(2)).myDryRun();

        org.mockito.Mockito.doReturn(ON).when(api).myDryRun();
        clock.advance(Duration.ofSeconds(2));
        assertThat(dryRun.isCurrentActive()).isTrue();
    }

    @Test
    void nobody_isNotInDryRun_andCatapultApiIsNotAsked() {
        assertThat(dryRun.isCurrentActive()).isFalse();
        verify(api, org.mockito.Mockito.never()).myDryRun();
    }

    @Test
    void withoutAnApiService_orWhenTheCurrentMemberCannotBeTold_nobodyIsInDryRun() {
        DryRunMembers none = new DryRunMembers(new StaticApplicationContext().getBeanProvider(ApiService.class), clock);
        assertThat(none.isCurrentActive()).isFalse();

        when(api.currentUserId()).thenThrow(new IllegalStateException("no request"));
        assertThat(dryRun.isCurrentActive()).isFalse();
    }

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-20T10:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
