package fr.enimaloc.catapult.web.ws.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WsAuthContextTest {

    @AfterEach
    void clear() {
        WsAuthContext.clear();
    }

    @Test
    void set_then_get_returns_the_jwt() {
        WsAuthContext.set("jwt-abc");
        assertThat(WsAuthContext.get()).isEqualTo("jwt-abc");
    }

    @Test
    void clear_removes_the_binding() {
        WsAuthContext.set("jwt-xyz");
        WsAuthContext.clear();
        assertThat(WsAuthContext.get()).isNull();
    }

    @Test
    void set_null_or_blank_acts_as_clear() {
        WsAuthContext.set("jwt-keep");
        WsAuthContext.set(null);
        assertThat(WsAuthContext.get()).isNull();

        WsAuthContext.set("jwt-keep");
        WsAuthContext.set("   ");
        assertThat(WsAuthContext.get()).isNull();
    }

    @Test
    void value_is_isolated_per_thread() throws Exception {
        WsAuthContext.set("main-jwt");
        var seenOnChild = new String[1];
        var t = new Thread(() -> seenOnChild[0] = WsAuthContext.get());
        t.start();
        t.join();
        assertThat(seenOnChild[0]).isNull();
        assertThat(WsAuthContext.get()).isEqualTo("main-jwt");
    }
}
