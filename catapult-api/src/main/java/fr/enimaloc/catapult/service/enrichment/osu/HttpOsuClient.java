package fr.enimaloc.catapult.service.enrichment.osu;

import fr.enimaloc.catapult.service.enrichment.EnrichmentUserAgent;
import fr.enimaloc.catapult.service.enrichment.osu.OsuData.Beatmap;
import fr.enimaloc.catapult.service.enrichment.osu.OsuData.Me;
import fr.enimaloc.catapult.service.enrichment.osu.OsuData.Score;
import fr.enimaloc.catapult.service.enrichment.osu.OsuData.Statistics;
import fr.enimaloc.catapult.service.enrichment.osu.OsuData.TokenGrant;
import fr.enimaloc.catapult.service.osu.OsuCredentialsService;
import fr.enimaloc.catapult.service.osu.OsuCredentialsService.Credentials;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@link OsuClient} over {@code https://osu.ppy.sh} (API v2). The member's token only travels in the
 * {@code Authorization} header, the client secret and the refresh token in the token request's form body; none is
 * ever put in a URL, an exception message or a log. Redirects are never followed and any 3xx is an error. A 2xx that
 * is not JSON or lacks what is expected ({@code {}}, {@code []} for an object, a user without {@code id} or
 * {@code statistics}) is an error, never "absent": osu! has no documented "not found" for a member's own data, so
 * every other answer is an outage. A 401 on a read is a refused token; {@code invalid_grant} on a renewal a refused
 * refresh token. A 429 pauses every call ({@code Retry-After}, at most {@link #MAX_PAUSE}). Every request asks the
 * {@link OsuRateLimiter} first, the token requests included. Asks for the score format of API version
 * {@link #API_VERSION}.
 */
@Component
public class HttpOsuClient implements OsuClient {

    static final String BASE = "https://osu.ppy.sh";
    static final String API = BASE + "/api/v2";
    static final String TOKEN = BASE + "/oauth/token";
    /** The response version: the score format with {@code ended_at}, {@code ruleset_id} and an accuracy in 0..1. */
    static final String API_VERSION = "20220705";
    /** The scopes the link was granted ({@code identify} is implicit, {@code public} reads the scores). */
    static final String SCOPES = "identify public";
    static final String USER_AGENT = EnrichmentUserAgent.VALUE;
    static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final Duration PAUSE = Duration.ofSeconds(60);
    /** The longest pause osu! can ask for: a larger one is not trusted. */
    static final Duration MAX_PAUSE = Duration.ofMinutes(10);

    private record Answer(int status, String retryAfter, byte[] body, boolean tooBig) {
    }

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RestClient restClient;
    private final OsuCredentialsService credentials;
    private final OsuRateLimiter limiter;
    private final Clock clock;

    @Autowired
    public HttpOsuClient(RestClient.Builder builder, OsuCredentialsService credentials, OsuRateLimiter limiter) {
        this(builder.requestFactory(requestFactory()).build(), credentials, limiter, Clock.systemUTC());
    }

    HttpOsuClient(RestClient restClient, OsuCredentialsService credentials, OsuRateLimiter limiter, Clock clock) {
        this.restClient = restClient;
        this.credentials = credentials;
        this.limiter = limiter;
        this.clock = clock;
    }

    /** The transport: never follows a redirect, 5 s to connect, 10 s to read. */
    static HttpClient httpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    static ClientHttpRequestFactory requestFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient());
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }

    // --- OAuth ---

    @Override
    public TokenGrant refresh(String refreshToken) {
        Credentials app = credentials.current()
                .orElseThrow(() -> new OsuUnavailableException("no osu! application is set"));
        String body = "client_id=" + form(app.clientId()) + "&client_secret=" + form(app.clientSecret())
                + "&grant_type=refresh_token&refresh_token=" + form(refreshToken) + "&scope=" + form(SCOPES);
        charge();
        Answer answer;
        try {
            answer = restClient.post().uri(TOKEN)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .header(HttpHeaders.USER_AGENT, USER_AGENT)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(body)
                    .exchange((req, res) -> read(res.getStatusCode().value(),
                            res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER), res.getBody()));
        } catch (RestClientException e) {
            throw new OsuUnavailableException("the osu! token request failed");
        }
        if (answer.status() >= 300 && answer.status() < 400) {
            throw new OsuUnavailableException("osu! answered a redirect (" + answer.status() + ")");
        }
        if (answer.tooBig()) {
            throw new OsuUnavailableException("the osu! answer is too big");
        }
        if (answer.status() == 429) {
            limiter.pause(pause(answer.retryAfter()));
            throw new OsuUnavailableException("osu! rate limit hit (429)");
        }
        JsonNode json = parseOrNull(answer);
        if ((answer.status() == 400 || answer.status() == 401) && json != null && refreshRefused(json)) {
            throw new OsuExceptions.RefreshRefused();
        }
        if (answer.status() < 200 || answer.status() >= 300) {
            throw new OsuUnavailableException("the osu! token request answered " + answer.status());
        }
        if (json == null || !json.isObject() || text(json.path("access_token")) == null
                || text(json.path("refresh_token")) == null) {
            throw garbled();
        }
        long expiresIn = json.path("expires_in").isNumber() ? json.get("expires_in").asLong() : 86_400;
        return new TokenGrant(json.get("access_token").asString(), clock.instant().plusSeconds(expiresIn),
                json.get("refresh_token").asString());
    }

    /**
     * Whether a token error says the refresh token itself is refused: {@code invalid_grant}, or (older Passport
     * versions) {@code invalid_request} about the refresh token. {@code invalid_client} is the application's own
     * failure, never the member's.
     */
    private static boolean refreshRefused(JsonNode json) {
        String error = text(json.path("error"));
        if ("invalid_grant".equals(error)) {
            return true;
        }
        String description = text(json.path("error_description"));
        return "invalid_request".equals(error) && description != null
                && description.toLowerCase(Locale.ROOT).contains("refresh token");
    }

    // --- reads ---

    @Override
    public Me me(String accessToken) {
        JsonNode me = get("/me", accessToken);
        if (!me.isObject() || !me.path("id").isIntegralNumber() || !me.path("statistics").isObject()) {
            throw garbled();
        }
        Map<String, Statistics> rulesets = new LinkedHashMap<>();
        JsonNode all = me.path("statistics_rulesets");
        if (all.isObject()) {
            for (Map.Entry<String, JsonNode> entry : all.properties()) {
                if (OsuData.RULESETS.contains(entry.getKey()) && entry.getValue().isObject()) {
                    rulesets.put(entry.getKey(), statistics(entry.getValue()));
                }
            }
        }
        String playmode = text(me.path("playmode"));
        return new Me(me.get("id").asLong(), text(me.path("username")),
                playmode != null && OsuData.RULESETS.contains(playmode) ? playmode : null,
                text(me.path("country_code")), statistics(me.get("statistics")), Map.copyOf(rulesets));
    }

    @Override
    public List<Score> recentScores(long osuUserId, String ruleset, int limit, String accessToken) {
        if (ruleset != null && !OsuData.RULESETS.contains(ruleset)) {
            throw new IllegalArgumentException("unknown osu! ruleset");
        }
        JsonNode scores = get("/users/" + osuUserId + "/scores/recent?include_fails=0&limit=" + limit
                + (ruleset == null ? "" : "&mode=" + ruleset), accessToken);
        if (!scores.isArray()) {
            throw garbled();
        }
        List<Score> list = new ArrayList<>();
        for (JsonNode s : scores) {
            if (!s.isObject() || !s.path("id").isIntegralNumber()) {
                throw garbled();
            }
            list.add(score(s));
        }
        return List.copyOf(list);
    }

    // --- HTTP ---

    /** A GET of the API with the member's token. */
    private JsonNode get(String path, String accessToken) {
        if (path.contains("?") ? !path.matches("/[A-Za-z0-9/_]+\\?[A-Za-z0-9=&_]+") : !path.matches("/[A-Za-z0-9/_]+")) {
            throw new IllegalArgumentException("unexpected osu! path");
        }
        charge();
        Answer answer;
        try {
            answer = restClient.get().uri(java.net.URI.create(API + path))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .header(HttpHeaders.USER_AGENT, USER_AGENT)
                    .header("x-api-version", API_VERSION)
                    .exchange((req, res) -> read(res.getStatusCode().value(),
                            res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER), res.getBody()));
        } catch (RestClientException e) {
            throw new OsuUnavailableException("the osu! request failed");
        }
        int status = answer.status();
        if (status >= 300 && status < 400) {
            throw new OsuUnavailableException("osu! answered a redirect (" + status + ")");
        }
        if (answer.tooBig()) {
            throw new OsuUnavailableException("the osu! answer is too big");
        }
        if (status == 429) {
            limiter.pause(pause(answer.retryAfter()));
            throw new OsuUnavailableException("osu! rate limit hit (429)");
        }
        if (status == 401) {
            throw new OsuExceptions.AuthRefused();
        }
        if (status < 200 || status >= 300) {
            throw new OsuUnavailableException("osu! answered " + status);
        }
        JsonNode json = parseOrNull(answer);
        if (json == null) {
            throw garbled();
        }
        return json;
    }

    private Answer read(int status, String retryAfter, InputStream body) throws IOException {
        if (status >= 300 && status < 400) {
            return new Answer(status, retryAfter, null, false);
        }
        byte[] bytes = body == null ? new byte[0] : body.readNBytes(MAX_BYTES + 1);
        return bytes.length > MAX_BYTES ? new Answer(status, retryAfter, null, true)
                : new Answer(status, retryAfter, bytes, false);
    }

    private void charge() {
        if (!limiter.budget().tryAcquire()) {
            throw new OsuUnavailableException("the osu! rate limit does not allow a request now");
        }
    }

    private JsonNode parseOrNull(Answer answer) {
        if (answer.body() == null || answer.body().length == 0) {
            return null;
        }
        try {
            return mapper.readTree(answer.body());
        } catch (JacksonException e) {
            return null;
        }
    }

    static Duration pause(String retryAfter) {
        try {
            long seconds = retryAfter == null ? 0 : Long.parseLong(retryAfter.trim());
            if (seconds <= 0) {
                return PAUSE;
            }
            Duration asked = Duration.ofSeconds(seconds);
            return asked.compareTo(MAX_PAUSE) > 0 ? MAX_PAUSE : asked;
        } catch (NumberFormatException e) {
            return PAUSE;
        }
    }

    // --- mapping ---

    private static OsuUnavailableException garbled() {
        return new OsuUnavailableException("the osu! answer does not have the expected shape");
    }

    private static Statistics statistics(JsonNode s) {
        Double accuracy = s.path("hit_accuracy").isNumber() ? s.get("hit_accuracy").asDouble()
                : s.path("accuracy").isNumber() ? s.get("accuracy").asDouble() * 100 : null;
        JsonNode level = s.path("level");
        return new Statistics(decimal(s.path("pp")), integer(s.path("global_rank")), integer(s.path("country_rank")),
                accuracy, longValue(s.path("play_count")), longValue(s.path("play_time")),
                integer(level.path("current")), decimal(level.path("progress")), integer(s.path("maximum_combo")),
                longValue(s.path("ranked_score")), s.path("is_ranked").asBoolean(false));
    }

    private static Score score(JsonNode s) {
        String ruleset = s.path("ruleset_id").isIntegralNumber() ? OsuData.ruleset(s.get("ruleset_id").asInt())
                : text(s.path("mode"));
        Double accuracy = s.path("accuracy").isNumber() ? s.get("accuracy").asDouble() * 100 : null;
        List<String> mods = new ArrayList<>();
        for (JsonNode mod : s.path("mods")) {
            String acronym = mod.isString() ? text(mod) : text(mod.path("acronym"));
            if (acronym != null && acronym.matches("[A-Z0-9]{1,4}")) {
                mods.add(acronym);
            }
        }
        Instant ended = instant(s.path("ended_at"));
        if (ended == null) {
            ended = instant(s.path("created_at"));
        }
        return new Score(s.get("id").asLong(), ruleset, text(s.path("rank")), decimal(s.path("pp")), accuracy,
                integer(s.path("max_combo")), List.copyOf(mods), s.path("passed").asBoolean(true), ended,
                beatmap(s.path("beatmap"), s.path("beatmapset")));
    }

    private static Beatmap beatmap(JsonNode map, JsonNode set) {
        if (!map.isObject() || !map.path("id").isIntegralNumber()) {
            return null;
        }
        long id = map.get("id").asLong();
        String url = text(map.path("url"));
        if (url == null || !url.startsWith(BASE + "/")) {
            url = BASE + "/b/" + id;
        }
        return new Beatmap(id, text(set.path("title")), text(set.path("artist")), text(map.path("version")),
                decimal(map.path("difficulty_rating")), url);
    }

    private static String form(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String text(JsonNode node) {
        return node != null && node.isString() && !node.asString().isBlank() ? node.asString() : null;
    }

    private static Double decimal(JsonNode node) {
        return node.isNumber() ? node.asDouble() : null;
    }

    private static Integer integer(JsonNode node) {
        return node.isIntegralNumber() ? node.asInt() : null;
    }

    private static Long longValue(JsonNode node) {
        return node.isIntegralNumber() ? node.asLong() : null;
    }

    private static Instant instant(JsonNode node) {
        String value = text(node);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            try {
                return java.time.OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException again) {
                return null;
            }
        }
    }
}
