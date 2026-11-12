package fr.enimaloc.catapult.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LearnedRateLimitInterceptorTest {

    private static final RouteKey PAGE = new RouteKey("store.steampowered.com", "GET", "/app/{id}");

    private LearnedRateLimiter limiter;
    private MockRestServiceServer server;
    private RestClient client;

    @BeforeEach
    void setUp() {
        limiter = mock(LearnedRateLimiter.class);
        when(limiter.learns("store.steampowered.com")).thenReturn(true);
        RestClient.Builder builder = RestClient.builder().requestInterceptor(new LearnedRateLimitInterceptor(limiter));
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
    }

    @Test
    void aCall_waitsForTheLimit_byItsTemplate_thenTellsTheAnswer() {
        server.expect(requestTo("https://store.steampowered.com/app/620?l=french")).andRespond(withSuccess());

        client.get().uri("https://store.steampowered.com/app/{id}?l={l}", 620, "french").retrieve().toBodilessEntity();

        verify(limiter).acquire(PAGE);
        verify(limiter).onResponse(eq(PAGE), eq(HttpStatus.OK), any(HttpHeaders.class));
    }

    @Test
    void a429_isTold_beforeTheCallerSeesIt() {
        server.expect(requestTo("https://store.steampowered.com/app/620")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.get().uri("https://store.steampowered.com/app/{id}", 620).retrieve()
                .toBodilessEntity()).isInstanceOf(HttpClientErrorException.TooManyRequests.class);

        verify(limiter).onResponse(eq(PAGE), eq(HttpStatus.TOO_MANY_REQUESTS), any(HttpHeaders.class));
    }

    @Test
    void aLocalRefusal_sendsNothing_andIsNotLearned() {
        HttpHeaders local = new HttpHeaders();
        local.set(LearnedRateLimiter.LOCAL_REFUSAL_HEADER, "true");
        doThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "local", local, new byte[0], null))
                .when(limiter).acquire(PAGE);

        assertThatThrownBy(() -> client.get().uri("https://store.steampowered.com/app/{id}", 620).retrieve()
                .toBodilessEntity()).isInstanceOf(HttpClientErrorException.TooManyRequests.class);

        verify(limiter, never()).onResponse(any(), any(), any());
        server.verify();
    }

    @Test
    void aDocumentedHost_passesUntouched() {
        server.expect(requestTo("https://api.steampowered.com/ISteamUser/x")).andRespond(withSuccess());

        client.get().uri("https://api.steampowered.com/ISteamUser/x").retrieve().toBodilessEntity();

        verify(limiter, never()).acquire(any());
        verify(limiter, never()).onResponse(any(), any(), any());
    }
}
