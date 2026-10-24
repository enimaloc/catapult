package fr.enimaloc.catapult.monitoring;

import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.debug.DebugMembers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.StaticApplicationContext;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A member in debug mode has their whole session replayed and every page traced, whatever the configured rates. */
class SentryBrowserDebugTest {
    private static final UUID DEBUGGED = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    private static SentryBrowser browser(UUID member, double traces, double replays) {
        ApiService api = mock(ApiService.class);
        when(api.currentUserId()).thenReturn(member);
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("api", api);
        ObjectProvider<ApiService> provider = context.getBeanProvider(ApiService.class);
        DebugMembers debug = mock(DebugMembers.class);
        when(debug.isActive(DEBUGGED)).thenReturn(true);
        context.getBeanFactory().registerSingleton("debug", debug);
        return new SentryBrowser("https://key@sentry.test/1", "production", traces, replays, Optional.empty(), provider, context.getBeanProvider(DebugMembers.class));
    }

    @Test
    void debuggedMember_getsFullReplayAndTraces() {
        // 0.1 is what the degraded profile caps the traces to: the debug override wins over it
        assertThat(browser(DEBUGGED, 0.1, 0).config().script())
                .containsEntry("tracesSampleRate", 1.0)
                .containsEntry("replaysSessionSampleRate", 1.0);
    }

    @Test
    void otherMember_keepsConfiguredRates() {
        assertThat(browser(OTHER, 0.1, 0.25).config().script())
                .containsEntry("tracesSampleRate", 0.1)
                .containsEntry("replaysSessionSampleRate", 0.25);
    }

    @Test
    void aVisitor_keepsConfiguredRates() {
        assertThat(browser(null, 0.1, 0).config().script())
                .containsEntry("tracesSampleRate", 0.1)
                .containsEntry("replaysSessionSampleRate", 0.0);
    }
}
