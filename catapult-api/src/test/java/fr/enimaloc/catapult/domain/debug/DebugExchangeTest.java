package fr.enimaloc.catapult.domain.debug;

import fr.enimaloc.catapult.domain.debug.DebugExchange.Direction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DebugExchangeTest {

    private static DebugExchange exchange(Direction direction, Integer status, String error) {
        return exchange("GET", direction, status, error);
    }

    private static DebugExchange exchange(String method, Direction direction, Integer status, String error) {
        DebugExchange e = new DebugExchange();
        e.setMethod(method);
        e.setDirection(direction);
        e.setStatus(status);
        e.setError(error);
        return e;
    }

    @Test
    void anOutgoingCall_failsWithoutStatus_withA4xxOr5xx_orWithAnError() {
        assertThat(exchange(Direction.OUT, 200, null).isError()).isFalse();
        assertThat(exchange(Direction.OUT, 399, null).isError()).isFalse();
        assertThat(exchange(Direction.OUT, 400, null).isError()).isTrue();
        assertThat(exchange(Direction.OUT, null, null).isError()).isTrue();
        assertThat(exchange(Direction.OUT, 200, "boom").isError()).isTrue();
    }

    @Test
    void anIncomingMessage_hasNoStatus_andFailsOnlyWithAnError() {
        assertThat(exchange(Direction.IN, null, null).isError()).isFalse();
        assertThat(exchange(Direction.IN, null, "invalid callback").isError()).isTrue();
        assertThat(exchange(Direction.IN, 500, null).isError()).isTrue();
    }

    @Test
    void aBrowserObsOrTwitchatEntry_failsOnlyWithAnError_whateverItsStatus() {
        for (String method : new String[]{"OBS", "TWITCHAT"}) {
            assertThat(exchange(method, Direction.OUT, null, null).isError()).as(method).isFalse();
            assertThat(exchange(method, Direction.IN, null, null).isError()).as(method).isFalse();
            assertThat(exchange(method, Direction.OUT, null, "connection closed").isError()).as(method).isTrue();
        }
        // obs-websocket's request statuses: 100 is a success, 200 on a failure, which the browser sends as an error.
        assertThat(exchange("OBS", Direction.OUT, 100, null).isError()).isFalse();
        assertThat(exchange("OBS", Direction.OUT, 600, "No source was found.").isError()).isTrue();
    }
}
