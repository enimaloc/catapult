package fr.enimaloc.catapult.web.ws.codec;

import fr.enimaloc.catapult.web.ws.codec.msg.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonMessageCodecTest {

    private final JsonMessageCodec codec = new JsonMessageCodec();

    @Test
    void roundtrip_auth_message() throws Exception {
        var msg = new AuthMessage("abc123");
        String json = codec.encode(msg);
        assertThat(json).contains("\"type\":\"auth\"").contains("\"token\":\"abc123\"");
        assertThat(codec.decodeIncoming(json)).isEqualTo(msg);
    }

    @Test
    void roundtrip_subscribe_message() throws Exception {
        var msg = new SubscribeMessage("events.global");
        assertThat(codec.decodeIncoming(codec.encode(msg))).isEqualTo(msg);
    }

    @Test
    void roundtrip_request_message() throws Exception {
        var msg = new RequestMessage("r-1", "search.games", Map.of("q", "skyrim", "limit", 5));
        WsIncoming decoded = codec.decodeIncoming(codec.encode(msg));
        assertThat(decoded).isInstanceOf(RequestMessage.class);
        assertThat(((RequestMessage) decoded).id()).isEqualTo("r-1");
        assertThat(((RequestMessage) decoded).action()).isEqualTo("search.games");
    }

    @Test
    void roundtrip_event_message() throws Exception {
        var msg = new EventMessage("events.global", "maintenance.scheduled", Map.of("startsAt", "2026-01-01T00:00:00Z"));
        String json = codec.encode(msg);
        assertThat(json).contains("\"type\":\"event\"").contains("\"channel\":\"events.global\"");
    }

    @Test
    void roundtrip_ping_message() throws Exception {
        var msg = new PingMessage(1750608000000L);
        assertThat(codec.encode(msg)).contains("\"type\":\"ping\"").contains("\"ts\":1750608000000");
    }

    @Test
    void roundtrip_response_message_ok() throws Exception {
        var msg = ResponseMessage.ok("r-1", List.of("a", "b"));
        String json = codec.encode(msg);
        assertThat(json).contains("\"type\":\"response\"").contains("\"ok\":true").contains("\"id\":\"r-1\"");
    }

    @Test
    void roundtrip_response_message_error() throws Exception {
        var msg = ResponseMessage.error("r-1", "RATE_LIMITED", "Too fast");
        String json = codec.encode(msg);
        assertThat(json).contains("\"ok\":false").contains("\"code\":\"RATE_LIMITED\"");
    }

    @Test
    void unknown_type_throws() {
        String bad = "{\"type\":\"unknown_xxx\"}";
        assertThatThrownBy(() -> codec.decodeIncoming(bad)).isInstanceOf(Exception.class);
    }

    @Test
    void malformed_json_throws() {
        assertThatThrownBy(() -> codec.decodeIncoming("not json")).isInstanceOf(Exception.class);
    }
}
