package fr.enimaloc.catapult.site;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SiteRoleTest {

    private static final String OURS = "95.170.26.38";
    private static final String OTHER = "82.67.50.147";

    @Mock ApplicationEventPublisher events;

    private final SiteProperties properties = new SiteProperties();
    private Set<String> answer = Set.of(OURS);
    private boolean dnsFails;
    private boolean writable = true;
    private int dnsCalls;
    private MutableClock clock;
    private SiteRole role;

    @BeforeEach
    void setUp() {
        properties.setPublicIp(OURS);
        clock = new MutableClock();
        DnsTargetResolver resolver = name -> {
            dnsCalls++;
            if (dnsFails) {
                throw new IOException("no server answered");
            }
            return answer;
        };
        role = new SiteRole(properties, resolver, () -> writable, events, new SimpleMeterRegistry(), clock);
    }

    @Test
    void unconfigured_isAlwaysActive_andNeverChecks() {
        properties.setPublicIp("");
        role.check();
        assertTrue(role.isActive());
        assertEquals(0, dnsCalls);
        verify(events, never()).publishEvent(org.mockito.ArgumentMatchers.any(org.springframework.context.ApplicationEvent.class));
    }

    @Test
    void startsInStandby_untilFirstSuccessfulCheck() {
        assertFalse(role.isActive());
        role.check();
        assertTrue(role.isActive());
        assertEquals(List.of(true), publishedStates(1));
    }

    @Test
    void listenersSeeTheNewStateWhenTheEventIsPublished() {
        boolean[] seen = new boolean[1];
        org.mockito.Mockito.doAnswer(i -> {
            seen[0] = role.isActive();
            return null;
        }).when(events).publishEvent(org.mockito.ArgumentMatchers.any(org.springframework.context.ApplicationEvent.class));
        role.check();
        assertTrue(seen[0]);
    }

    @Test
    void dnsPointingElsewhere_goesStandbyImmediately() {
        role.check();
        answer = Set.of(OTHER);
        role.check();
        assertFalse(role.isActive());
        assertEquals(List.of(true, false), publishedStates(2));
    }

    @Test
    void disagreeingServers_isNotActive() {
        answer = Set.of(OURS, OTHER);
        role.check();
        assertFalse(role.isActive());
    }

    @Test
    void databaseInRecovery_isNotActive() {
        writable = false;
        role.check();
        assertFalse(role.isActive());
    }

    @Test
    void resolutionFailure_keepsStateWithinGrace_thenStandby() {
        role.check();
        dnsFails = true;
        clock.advance(Duration.ofSeconds(149));
        role.check();
        assertTrue(role.isActive());
        clock.advance(Duration.ofSeconds(2));
        role.check();
        assertFalse(role.isActive());
    }

    @Test
    void flappingWithinGrace_publishesNoTransition() {
        role.check();
        for (int i = 0; i < 2; i++) {
            dnsFails = true;
            clock.advance(Duration.ofSeconds(30));
            role.check();
            dnsFails = false;
            clock.advance(Duration.ofSeconds(30));
            role.check();
        }
        assertEquals(List.of(true), publishedStates(1));
    }

    @Test
    void requireActive_throwsInStandby() {
        assertThrows(StandbyException.class, () -> role.requireActive("chat"));
        role.check();
        role.requireActive("chat");
    }

    private List<Boolean> publishedStates(int expected) {
        ArgumentCaptor<org.springframework.context.ApplicationEvent> captor = ArgumentCaptor.forClass(org.springframework.context.ApplicationEvent.class);
        verify(events, times(expected)).publishEvent(captor.capture());
        return captor.getAllValues().stream().map(e -> ((SiteRoleChangedEvent) e).isActive()).toList();
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-03T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
