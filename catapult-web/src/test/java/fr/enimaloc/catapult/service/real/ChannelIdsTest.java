package fr.enimaloc.catapult.service.real;

import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ChannelIdsTest {

    private static final String API = "http://api.test";
    private static final String LOOKUP = API + "/api/internal/users?twitchUsername=streamer";
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
    private static final String FOUND = "{\"id\":\"" + ID + "\",\"twitchUsername\":\"streamer\"}";

    private MockRestServiceServer api;
    private ApiClient client;
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-26T12:00:00Z"));

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        api = MockRestServiceServer.bindTo(builder).build();
        client = new ApiClient(API, builder);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute(ApiClient.SESSION_JWT_KEY, "session-jwt");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void verify() {
        RequestContextHolder.resetRequestAttributes();
        api.verify();
    }

    private ResponseActions lookup() {
        return api.expect(once(), requestTo(LOOKUP)).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer session-jwt"));
    }

    @Test
    void resolve_cachesAFoundId() {
        lookup().andRespond(withSuccess(FOUND, MediaType.APPLICATION_JSON));
        ChannelIds ids = new ChannelIds(client, clock);

        assertThat(ids.resolve("streamer")).isEqualTo(new ChannelIds.Lookup(200, ID));
        assertThat(ids.resolve("streamer").found()).isTrue();
        // api.verify(): one request for both
    }

    @Test
    void resolve_doesNotCache404Nor401() {
        lookup().andRespond(withStatus(HttpStatus.NOT_FOUND));
        lookup().andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        lookup().andRespond(withSuccess(FOUND, MediaType.APPLICATION_JSON));
        ChannelIds ids = new ChannelIds(client, clock);

        ChannelIds.Lookup unknown = ids.resolve("streamer");
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.found()).isFalse();
        assertThat(ids.resolve("streamer")).extracting(ChannelIds.Lookup::status, ChannelIds.Lookup::found)
                .containsExactly(401, false);
        assertThat(ids.resolve("streamer").id()).isEqualTo(ID);
    }

    @Test
    void resolve_expiresAfterSixtySeconds() {
        lookup().andRespond(withSuccess(FOUND, MediaType.APPLICATION_JSON));
        lookup().andRespond(withSuccess(FOUND, MediaType.APPLICATION_JSON));
        ChannelIds ids = new ChannelIds(client, clock);

        ids.resolve("streamer");
        clock.advance(Duration.ofSeconds(59));
        ids.resolve("streamer");
        clock.advance(Duration.ofSeconds(2));
        ids.resolve("streamer");
    }

    @Test
    void forget_dropsTheEntry() {
        lookup().andRespond(withSuccess(FOUND, MediaType.APPLICATION_JSON));
        lookup().andRespond(withSuccess(FOUND, MediaType.APPLICATION_JSON));
        ChannelIds ids = new ChannelIds(client, clock);

        ids.resolve("streamer");
        ids.forget("streamer");
        ids.resolve("streamer");
    }

    @Test
    void resolve_aSuccessWithoutAnIdIsABadGateway_notKept() {
        lookup().andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        lookup().andRespond(withSuccess(FOUND, MediaType.APPLICATION_JSON));
        ChannelIds ids = new ChannelIds(client, clock);

        assertThat(ids.resolve("streamer")).isEqualTo(new ChannelIds.Lookup(502, null));
        assertThat(ids.resolve("streamer").found()).isTrue();
    }

    @Test
    void theSpringConstructorUsesTheSystemClock() {
        lookup().andRespond(withSuccess(FOUND, MediaType.APPLICATION_JSON));

        assertThat(new ChannelIds(client).resolve("streamer").id()).isEqualTo(ID);
    }

    /** A clock the test moves forward. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
