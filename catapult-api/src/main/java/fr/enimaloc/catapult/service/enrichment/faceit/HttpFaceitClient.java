package fr.enimaloc.catapult.service.enrichment.faceit;

import fr.enimaloc.catapult.getter.faceit.FaceitApiKeyRotator;
import fr.enimaloc.catapult.service.enrichment.EnrichmentUserAgent;
import fr.enimaloc.catapult.service.enrichment.faceit.FaceitModels.GameProfile;
import fr.enimaloc.catapult.service.enrichment.faceit.FaceitModels.LastMatch;
import fr.enimaloc.catapult.service.enrichment.faceit.FaceitModels.Lifetime;
import fr.enimaloc.catapult.service.enrichment.faceit.FaceitModels.Player;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link FaceitClient} over {@code https://open.faceit.com/data/v4}, with a key of the shared pool. The key only
 * travels in the {@code Authorization: Bearer} header (never in a URL), and no key or body is ever put in an
 * exception message or a log. Redirects are never followed and any 3xx is an error; a 2xx that is not JSON or not of
 * the expected shape is an error, never "absent"; only FACEIT's JSON 404 ({@code {"errors":[{"http_status":404}]}})
 * means "not found". A key FACEIT refuses (401, 403) is set aside and the call is tried once more with another key. A
 * 429 pauses every call (its {@code Retry-After}, at most {@link #MAX_PAUSE}). Every request asks the
 * {@link FaceitRateLimiter} first.
 *
 * <p>The lifetime and match stats are free-form maps in FACEIT's documentation: the names read here
 * ({@code Matches}, {@code Win Rate %}, {@code Map}, {@code Score}…) are not verified on a live answer.
 */
@Component
public class HttpFaceitClient implements FaceitClient {

    static final String API = "https://open.faceit.com/data/v4";
    static final int MAX_BYTES = 1024 * 1024;
    private static final Duration PAUSE = Duration.ofSeconds(60);
    /** The longest pause a 429 can ask for: a larger Retry-After is not trusted. */
    static final Duration MAX_PAUSE = Duration.ofMinutes(10);
    /** How long a key FACEIT answered 401 is set aside. */
    static final Duration REJECTED_FOR = Duration.ofHours(1);
    /** How long a key FACEIT answered 403 is set aside (a revoked key, or a route it may not use). */
    static final Duration FORBIDDEN_FOR = Duration.ofMinutes(5);

    private record Answer(int status, String retryAfter, byte[] body, boolean tooBig) {
    }

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RestClient restClient;
    private final FaceitApiKeyRotator keys;
    private final FaceitRateLimiter limiter;
    private final String userAgent;

    @Autowired
    public HttpFaceitClient(RestClient.Builder builder, FaceitApiKeyRotator keys, FaceitRateLimiter limiter,
                            @Value("${app.base-url}") String baseUrl) {
        this(builder.requestFactory(requestFactory()).build(), keys, limiter, EnrichmentUserAgent.of(baseUrl));
    }

    HttpFaceitClient(RestClient restClient, FaceitApiKeyRotator keys, FaceitRateLimiter limiter, String userAgent) {
        this.restClient = restClient;
        this.keys = keys;
        this.limiter = limiter;
        this.userAgent = userAgent;
    }

    /** The transport: never follows a redirect, 5 s to connect. */
    static HttpClient httpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** 10 s to read. */
    static ClientHttpRequestFactory requestFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient());
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }

    // --- players ---

    @Override
    public Player playerByNickname(String nickname) {
        return player(get(API + "/players?nickname={nickname}", nickname));
    }

    @Override
    public Player player(String playerId) {
        return player(get(API + "/players/{id}", playerId));
    }

    private static Player player(JsonNode json) {
        object(json);
        String id = text(json.path("player_id"));
        String nickname = text(json.path("nickname"));
        if (id == null || nickname == null) {
            throw garbled();
        }
        JsonNode games = json.path("games");
        if (!games.isMissingNode() && !games.isNull() && !games.isObject()) {
            throw garbled();
        }
        JsonNode cs2 = games.path("cs2");
        GameProfile profile = cs2.isObject() ? new GameProfile(integer(cs2.path("skill_level")),
                integer(cs2.path("faceit_elo")), text(cs2.path("region"))) : null;
        return new Player(id, nickname, text(json.path("avatar")), text(json.path("country")),
                text(json.path("faceit_url")), profile);
    }

    // --- stats ---

    @Override
    public Lifetime lifetime(String playerId, String game) {
        JsonNode json = object(get(API + "/players/{id}/stats/{game}", playerId, game));
        JsonNode lifetime = json.path("lifetime");
        if (!lifetime.isObject()) {
            throw garbled();
        }
        List<Boolean> recent = new ArrayList<>();
        for (JsonNode result : lifetime.path("Recent Results")) {
            Integer value = integer(result);
            if (value != null) {
                recent.add(value == 1);
            }
        }
        return new Lifetime(integer(lifetime.path("Matches")), integer(lifetime.path("Wins")),
                integer(lifetime.path("Win Rate %")), decimal(lifetime.path("Average K/D Ratio")),
                integer(lifetime.path("Average Headshots %")), integer(lifetime.path("Current Win Streak")),
                integer(lifetime.path("Longest Win Streak")), recent);
    }

    @Override
    public Optional<LastMatch> lastMatch(String playerId, String game) {
        JsonNode json = object(get(API + "/players/{id}/games/{game}/stats?offset=0&limit=1", playerId, game));
        JsonNode items = json.path("items");
        if (!items.isArray()) {
            throw garbled();
        }
        if (items.isEmpty()) {
            return Optional.empty();
        }
        JsonNode stats = items.get(0).path("stats");
        if (!stats.isObject()) {
            throw garbled();
        }
        Integer result = integer(stats.path("Result"));
        Long finished = longValue(stats.path("Match Finished At"));
        return Optional.of(new LastMatch(text(stats.path("Match Id")), text(stats.path("Map")),
                text(stats.path("Score")), result == null ? null : result == 1, integer(stats.path("Kills")),
                integer(stats.path("Deaths")), decimal(stats.path("K/D Ratio")),
                finished == null || finished <= 0 ? null : Instant.ofEpochMilli(finished)));
    }

    // --- HTTP ---

    /** GET with a key of the pool; a refused key is set aside and another one tried once. */
    private JsonNode get(String template, Object... vars) {
        String tried = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            String key = keys.nextKey().orElseThrow(() -> new FaceitUnavailableException("no FACEIT API key"));
            if (key.equals(tried)) {
                break;
            }
            tried = key;
            Answer answer = send(template, key, vars);
            if (answer.status() == 401 || answer.status() == 403) {
                keys.onKeyRejected(key, answer.status() == 401 ? REJECTED_FOR : FORBIDDEN_FOR);
                continue;
            }
            if (answer.status() == 429) {
                keys.onKeyRateLimited(key, retryAfter(answer.retryAfter()));
            }
            return interpret(answer);
        }
        throw new FaceitUnavailableException("FACEIT refused the API key");
    }

    /** Charges the limiter, then sends; the key is only ever put in the {@code Authorization} header. */
    private Answer send(String template, String key, Object... vars) {
        if (limiter.paused() || !limiter.budget().tryAcquire()) {
            throw new FaceitUnavailableException("the FACEIT rate limit does not allow a request now");
        }
        keys.onCall(key);
        try {
            return restClient.get().uri(template, vars).header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                    .header(HttpHeaders.ACCEPT, "application/json").header(HttpHeaders.USER_AGENT, userAgent)
                    .exchange((req, res) -> {
                        int status = res.getStatusCode().value();
                        String retryAfter = res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
                        if (status != 404 && !res.getStatusCode().is2xxSuccessful()) {
                            return new Answer(status, retryAfter, null, false);
                        }
                        byte[] body = readLimited(res.getBody(), MAX_BYTES);
                        return new Answer(status, retryAfter, body, body == null);
                    });
        } catch (RestClientException e) {
            throw new FaceitUnavailableException("the FACEIT request failed");
        }
    }

    /** At most {@code max} bytes; null when there are more. */
    static byte[] readLimited(InputStream in, int max) throws IOException {
        byte[] bytes = in.readNBytes(max + 1);
        return bytes.length > max ? null : bytes;
    }

    private JsonNode interpret(Answer answer) {
        int status = answer.status();
        if (status == 429) {
            limiter.pause(retryAfter(answer.retryAfter()));
            throw new FaceitUnavailableException("FACEIT rate limit hit (429)");
        }
        if (answer.tooBig()) {
            throw new FaceitUnavailableException("the FACEIT answer is too big");
        }
        if (status == 404) {
            if (isNotFound(parseOrNull(answer.body()))) {
                throw new FaceitNotFoundException("FACEIT does not know it");
            }
            throw new FaceitUnavailableException("FACEIT answered 404 without a recognised body");
        }
        if (status < 200 || status >= 300) {
            throw new FaceitUnavailableException("FACEIT answered " + status);
        }
        JsonNode json = parseOrNull(answer.body());
        if (json == null) {
            throw new FaceitUnavailableException("the FACEIT answer is not JSON");
        }
        return json;
    }

    /** FACEIT's error shape ({@code {"errors":[{"message", "code", "http_status"}]}}) saying 404. */
    private static boolean isNotFound(JsonNode body) {
        if (body == null || !body.path("errors").isArray()) {
            return false;
        }
        for (JsonNode error : body.get("errors")) {
            if (error.path("http_status").isNumber() && error.get("http_status").asInt() == 404) {
                return true;
            }
        }
        return false;
    }

    private JsonNode parseOrNull(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return mapper.readTree(body);
        } catch (JacksonException e) {
            return null;
        }
    }

    static Duration retryAfter(String header) {
        try {
            long seconds = header == null ? 0 : Long.parseLong(header.trim());
            if (seconds <= 0) {
                return PAUSE;
            }
            return seconds > MAX_PAUSE.toSeconds() ? MAX_PAUSE : Duration.ofSeconds(seconds);
        } catch (NumberFormatException e) {
            return PAUSE;
        }
    }

    // --- mapping ---

    private static FaceitUnavailableException garbled() {
        return new FaceitUnavailableException("the FACEIT answer does not have the expected shape");
    }

    private static JsonNode object(JsonNode node) {
        if (!node.isObject() || node.isEmpty()) {
            throw garbled();
        }
        return node;
    }

    private static String text(JsonNode node) {
        return node != null && node.isString() && !node.asString().isBlank() ? node.asString() : null;
    }

    /** A whole number, which FACEIT's stats send as text ({@code "1234"}); null when it is not one. */
    private static Integer integer(JsonNode node) {
        if (node.isIntegralNumber()) {
            return node.asInt();
        }
        if (node.isString()) {
            try {
                return Integer.parseInt(node.asString().strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Long longValue(JsonNode node) {
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isString()) {
            try {
                return Long.parseLong(node.asString().strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** A decimal number, as a number or as text ({@code "1.12"}); null when it is not one. */
    private static Double decimal(JsonNode node) {
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isString()) {
            try {
                double value = Double.parseDouble(node.asString().strip());
                return Double.isFinite(value) ? value : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
