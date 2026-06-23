package fr.enimaloc.catapult.web.ws.lifecycle;

import fr.enimaloc.catapult.web.ws.WsHub;
import fr.enimaloc.catapult.web.ws.codec.msg.EventMessage;
import fr.enimaloc.catapult.web.ws.dispatch.ChannelResolver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.event.ContextClosedEvent;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GracefulShutdownBroadcasterTest {

    @Test
    void on_context_closed_broadcasts_maintenance_imminent_directly() {
        WsHub hub = mock(WsHub.class);
        ChannelResolver resolver = new ChannelResolver();
        var broadcaster = new GracefulShutdownBroadcaster(hub, resolver);

        broadcaster.onContextClosed(new ContextClosedEvent(mock(org.springframework.context.ApplicationContext.class)));

        ArgumentCaptor<EventMessage> captor = ArgumentCaptor.forClass(EventMessage.class);
        verify(hub).broadcast(eq("events.global"), captor.capture());
        EventMessage event = captor.getValue();
        assertThat(event.channel()).isEqualTo("events.global");
        assertThat(event.name()).isEqualTo("maintenance.imminent");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) event.data();
        assertThat(data).containsEntry("reason", "shutdown");
        assertThat(data).containsEntry("etaSeconds", GracefulShutdownBroadcaster.DEFAULT_ETA_SECONDS);
    }

    @Test
    void hub_failure_does_not_propagate() {
        WsHub hub = mock(WsHub.class);
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(hub).broadcast(any(), any());
        var broadcaster = new GracefulShutdownBroadcaster(hub, new ChannelResolver());

        // Must not throw — shutdown sequence is allowed to be best-effort.
        broadcaster.onContextClosed(new ContextClosedEvent(mock(org.springframework.context.ApplicationContext.class)));
    }

    @Test
    void child_context_close_is_ignored() {
        WsHub hub = mock(WsHub.class);
        var broadcaster = new GracefulShutdownBroadcaster(hub, new ChannelResolver());

        // Simulate the management (actuator) context whose parent is the main app context.
        var childCtx = mock(org.springframework.context.ApplicationContext.class);
        var parentCtx = mock(org.springframework.context.ApplicationContext.class);
        when(childCtx.getParent()).thenReturn(parentCtx);

        broadcaster.onContextClosed(new ContextClosedEvent(childCtx));

        verify(hub, never()).broadcast(any(), any());
    }

    @Test
    void single_fire_guard_blocks_duplicate_broadcasts() {
        WsHub hub = mock(WsHub.class);
        var broadcaster = new GracefulShutdownBroadcaster(hub, new ChannelResolver());

        var rootCtx = mock(org.springframework.context.ApplicationContext.class);
        // root context: getParent() returns null by default
        broadcaster.onContextClosed(new ContextClosedEvent(rootCtx));
        broadcaster.onContextClosed(new ContextClosedEvent(rootCtx));

        // Only the first call should reach the hub.
        verify(hub, times(1)).broadcast(any(), any());
    }
}
