package fr.enimaloc.catapult.service.enrichment.blizzard;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The Blizzard application (client credentials) token, kept in memory only and renewed when absent or within an
 * hour of its expiry. One renewal runs at a time: the thread that starts it does the network call outside any lock,
 * the others wait for its result for at most {@value #WAIT_SECONDS} seconds. The token request is charged to the
 * {@link BlizzardRateLimiter}. The token and the response body never appear in a message or a log.
 */
@Service
public class BlizzardAppTokenService {

    private static final String URL = "https://oauth.battle.net/token";
    private static final Duration RENEW_BEFORE = Duration.ofHours(1);
    static final int WAIT_SECONDS = 5;

    private record Fresh(String value, Instant renewAfter) {
    }

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RestClient restClient;
    private final BlizzardRateLimiter limiter;
    private final String clientId;
    private final String clientSecret;
    private final Clock clock;

    private String cached;
    private Instant renewAfter = Instant.MIN;
    private CompletableFuture<String> inFlight;

    @Autowired
    public BlizzardAppTokenService(RestClient.Builder builder, BlizzardRateLimiter limiter,
                                   @Value("${battlenet.client-id:}") String clientId,
                                   @Value("${battlenet.client-secret:}") String clientSecret) {
        this(builder.requestFactory(HttpBlizzardClient.requestFactory()).build(), limiter, clientId, clientSecret,
                Clock.systemUTC());
    }

    BlizzardAppTokenService(RestClient restClient, BlizzardRateLimiter limiter, String clientId,
                            String clientSecret, Clock clock) {
        this.restClient = restClient;
        this.limiter = limiter;
        this.clientId = clientId == null ? "" : clientId;
        this.clientSecret = clientSecret == null ? "" : clientSecret;
        this.clock = clock;
    }

    /** Whether the client credentials are configured. */
    public boolean available() {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }

    /** A valid application token, renewed when needed. */
    public String token() {
        if (!available()) {
            throw new BlizzardUnavailableException("Blizzard client credentials are not configured");
        }
        CompletableFuture<String> mine = null;
        CompletableFuture<String> theirs = null;
        synchronized (this) {
            if (cached != null && clock.instant().isBefore(renewAfter)) {
                return cached;
            }
            if (inFlight != null) {
                theirs = inFlight;
            } else {
                mine = new CompletableFuture<>();
                inFlight = mine;
            }
        }
        if (mine != null) {
            return renew(mine);
        }
        try {
            return theirs.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new BlizzardUnavailableException("Blizzard token renewal is taking too long");
        } catch (ExecutionException e) {
            throw new BlizzardUnavailableException("Blizzard token renewal failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BlizzardUnavailableException("interrupted while waiting for the Blizzard token");
        }
    }

    private String renew(CompletableFuture<String> mine) {
        try {
            Fresh fresh = request();
            synchronized (this) {
                cached = fresh.value();
                renewAfter = fresh.renewAfter();
                inFlight = null;
            }
            mine.complete(fresh.value());
            return fresh.value();
        } catch (Throwable e) {
            synchronized (this) {
                inFlight = null;
            }
            mine.completeExceptionally(e);
            throw e;
        }
    }

    /** Forgets {@code token} (Blizzard refused it); another token already renewed is kept. */
    public synchronized void invalidate(String token) {
        if (cached != null && token != null
                && MessageDigest.isEqual(cached.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            cached = null;
            renewAfter = Instant.MIN;
        }
    }

    private Fresh request() {
        if (!limiter.budget().tryAcquire()) {
            throw new BlizzardUnavailableException("the Blizzard rate limit does not allow a token request now");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        Object[] answer;
        try {
            answer = restClient.post().uri(URL).headers(h -> h.setBasicAuth(clientId, clientSecret))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).accept(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.USER_AGENT, HttpBlizzardClient.USER_AGENT).body(form)
                    .exchange((req, res) -> new Object[]{res.getStatusCode().value(),
                            res.getStatusCode().is2xxSuccessful()
                                    ? HttpBlizzardClient.readLimited(res.getBody(), HttpBlizzardClient.MAX_BYTES)
                                    : null});
        } catch (RestClientException e) {
            throw new BlizzardUnavailableException("the Blizzard token request failed");
        }
        int status = (Integer) answer[0];
        if (status < 200 || status >= 300) {
            throw new BlizzardUnavailableException("the Blizzard token endpoint answered " + status);
        }
        if (answer[1] == null) {
            throw new BlizzardUnavailableException("the Blizzard token answer is too big");
        }
        try {
            JsonNode json = mapper.readTree((byte[]) answer[1]);
            JsonNode token = json.path("access_token");
            JsonNode expires = json.path("expires_in");
            if (!token.isString() || token.asString().isBlank() || !expires.isNumber() || expires.asLong() <= 0) {
                throw new BlizzardUnavailableException("the Blizzard token answer is not the expected one");
            }
            long lifetime = expires.asLong();
            long usable = Math.max(lifetime - RENEW_BEFORE.toSeconds(), lifetime / 2);
            Instant renewAt = clock.instant().plusSeconds(usable);
            return new Fresh(token.asString(), renewAt);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new BlizzardUnavailableException("the Blizzard token answer is not JSON");
        }
    }
}
