package fr.enimaloc.catapult.web.ws.dispatch;

import fr.enimaloc.catapult.web.ws.WsSession;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WsRequestDispatcherTest {

    private static WsSession anonymousSession() {
        WebSocketSession spring = mock(WebSocketSession.class);
        when(spring.getId()).thenReturn("s-anon");
        return new WsSession(spring);
    }

    private static WsSession authenticatedSession(boolean admin) {
        WebSocketSession spring = mock(WebSocketSession.class);
        when(spring.getId()).thenReturn("s-auth");
        WsSession s = new WsSession(spring);
        s.authenticate(UUID.randomUUID(), admin ? Set.of("ROLE_USER", "ROLE_ADMIN") : Set.of("ROLE_USER"));
        return s;
    }

    private static RequestHandler handler(String action, boolean auth, boolean admin, Object result) {
        return new RequestHandler() {
            @Override public String action() { return action; }
            @Override public boolean requiresAuth() { return auth; }
            @Override public boolean requiresAdmin() { return admin; }
            @Override public Object handle(WsSession session, Object params) { return result; }
        };
    }

    @Test
    void resolves_and_invokes_handler() throws Exception {
        var d = new WsRequestDispatcher(List.of(handler("a.b", false, false, "ok")));
        Object out = d.dispatch(anonymousSession(), "a.b", null);
        assertThat(out).isEqualTo("ok");
    }

    @Test
    void unknown_action_throws_UNKNOWN_ACTION() {
        var d = new WsRequestDispatcher(List.of());
        assertThatThrownBy(() -> d.dispatch(anonymousSession(), "nope", null))
                .isInstanceOf(WsBusinessException.class)
                .hasMessageContaining("Unknown")
                .satisfies(e -> assertThat(((WsBusinessException) e).code()).isEqualTo("UNKNOWN_ACTION"));
    }

    @Test
    void blank_action_throws_UNKNOWN_ACTION() {
        var d = new WsRequestDispatcher(List.of());
        assertThatThrownBy(() -> d.dispatch(anonymousSession(), "", null))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(e -> assertThat(((WsBusinessException) e).code()).isEqualTo("UNKNOWN_ACTION"));
    }

    @Test
    void requires_auth_rejected_for_anonymous() {
        var d = new WsRequestDispatcher(List.of(handler("a.b", true, false, "ok")));
        assertThatThrownBy(() -> d.dispatch(anonymousSession(), "a.b", null))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(e -> assertThat(((WsBusinessException) e).code()).isEqualTo("UNAUTHENTICATED"));
    }

    @Test
    void requires_auth_passes_for_authenticated() throws Exception {
        var d = new WsRequestDispatcher(List.of(handler("a.b", true, false, "ok")));
        assertThat(d.dispatch(authenticatedSession(false), "a.b", null)).isEqualTo("ok");
    }

    @Test
    void requires_admin_rejected_for_non_admin() {
        var d = new WsRequestDispatcher(List.of(handler("a.b", true, true, "ok")));
        assertThatThrownBy(() -> d.dispatch(authenticatedSession(false), "a.b", null))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(e -> assertThat(((WsBusinessException) e).code()).isEqualTo("FORBIDDEN"));
    }

    @Test
    void requires_admin_passes_for_admin() throws Exception {
        var d = new WsRequestDispatcher(List.of(handler("a.b", true, true, "ok")));
        assertThat(d.dispatch(authenticatedSession(true), "a.b", null)).isEqualTo("ok");
    }

    @Test
    void duplicate_action_fails_fast_at_construction() {
        assertThatThrownBy(() -> new WsRequestDispatcher(List.of(
                handler("dup", false, false, 1),
                handler("dup", false, false, 2))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate WS handler");
    }

    @Test
    void search_action_burst_rate_limits_after_10() throws Exception {
        var d = new WsRequestDispatcher(List.of(handler("search.foo", false, false, "ok")));
        WsSession sess = authenticatedSession(false);
        // 10 must pass; 11th hits the per-session search bucket.
        for (int i = 0; i < 10; i++) {
            d.dispatch(sess, "search.foo", null);
        }
        assertThatThrownBy(() -> d.dispatch(sess, "search.foo", null))
                .isInstanceOf(WsBusinessException.class)
                .satisfies(e -> assertThat(((WsBusinessException) e).code()).isEqualTo("RATE_LIMITED"));
    }
}
