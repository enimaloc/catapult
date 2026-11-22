package fr.enimaloc.catapult.service.enrichment.bungie;

import fr.enimaloc.catapult.service.bungie.BungieCredentialsService;
import fr.enimaloc.catapult.service.bungie.BungieCredentialsService.Credentials;
import fr.enimaloc.catapult.service.enrichment.bungie.BungieData.Activity;
import fr.enimaloc.catapult.service.enrichment.bungie.BungieData.Character;
import fr.enimaloc.catapult.service.enrichment.bungie.BungieData.CurrentUser;
import fr.enimaloc.catapult.service.enrichment.bungie.BungieData.Definition;
import fr.enimaloc.catapult.service.enrichment.bungie.BungieData.DestinyMembership;
import fr.enimaloc.catapult.service.enrichment.bungie.BungieData.Profile;
import fr.enimaloc.catapult.service.enrichment.bungie.BungieData.Season;
import fr.enimaloc.catapult.service.enrichment.bungie.BungieData.TokenGrant;
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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link BungieClient} over {@code https://www.bungie.net}. The API key only travels in the {@code X-API-Key} header,
 * the member's token in the {@code Authorization} header, the client secret in the token request's Basic header and
 * the refresh token in its form body; none is ever put in a URL, an exception message or a log. Redirects are never
 * followed and any 3xx is an error. Bungie wraps every answer in {@code {Response, ErrorCode, ThrottleSeconds, …}}:
 * {@code ErrorCode 1} with a {@code Response} of the expected shape is the only success, a 2xx that is not JSON or
 * lacks it is an error, never "absent"; only {@code DestinyAccountNotFound} (1601) means "not found" and
 * {@code DestinyPrivacyRestriction} (1665) "private". A {@code ThrottleSeconds} or a 429 pauses every call. Every
 * request asks the {@link BungieRateLimiter} first.
 */
@Component
public class HttpBungieClient implements BungieClient {

    static final String PLATFORM = "https://www.bungie.net/Platform";
    static final String TOKEN = "https://www.bungie.net/platform/app/oauth/token/";
    static final String USER_AGENT = "Catapult/1.0 (+https://catapult.enimaloc.fr)";
    static final int MAX_BYTES = 4 * 1024 * 1024;
    /** The records component lists every triumph of every character: larger than the others. */
    static final int MAX_RECORD_BYTES = 8 * 1024 * 1024;
    private static final Duration PAUSE = Duration.ofSeconds(60);
    /** The longest pause Bungie can ask for: a larger one is not trusted. */
    private static final Duration MAX_PAUSE = Duration.ofMinutes(10);
    private static final Duration REFRESH_LIFETIME = Duration.ofDays(90);

    private static final int SUCCESS = 1;
    private static final int ACCOUNT_NOT_FOUND = 1601;
    private static final int PRIVACY_RESTRICTION = 1665;
    /** {@code WebAuthRequired}, {@code AccessTokenHasExpired}: the access token is not (or no longer) good. */
    private static final Set<Integer> AUTH_REFUSED = Set.of(99, 2111);

    private record Answer(int status, String retryAfter, byte[] body, boolean tooBig) {
    }

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RestClient restClient;
    private final BungieCredentialsService credentials;
    private final BungieRateLimiter limiter;
    private final Clock clock;

    @Autowired
    public HttpBungieClient(RestClient.Builder builder, BungieCredentialsService credentials,
                            BungieRateLimiter limiter) {
        this(builder.requestFactory(requestFactory()).build(), credentials, limiter, Clock.systemUTC());
    }

    HttpBungieClient(RestClient restClient, BungieCredentialsService credentials, BungieRateLimiter limiter,
                     Clock clock) {
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
        Credentials app = app();
        String basic = Base64.getEncoder().encodeToString((form(app.clientId()) + ":" + form(app.clientSecret()))
                .getBytes(StandardCharsets.UTF_8));
        String body = "grant_type=refresh_token&refresh_token=" + form(refreshToken);
        charge();
        Answer answer;
        try {
            answer = restClient.post().uri(TOKEN).header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .header(HttpHeaders.USER_AGENT, USER_AGENT)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(body)
                    .exchange((req, res) -> read(res.getStatusCode().value(),
                            res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER), res.getBody(), MAX_BYTES));
        } catch (RestClientException e) {
            throw new BungieUnavailableException("the Bungie token request failed");
        }
        if (answer.status() == 429) {
            limiter.pause(pause(answer.retryAfter()));
            throw new BungieUnavailableException("Bungie rate limit hit (429)");
        }
        JsonNode json = parseOrNull(answer);
        if ((answer.status() == 400 || answer.status() == 401) && json != null
                && "invalid_grant".equals(text(json.path("error")))) {
            throw new BungieExceptions.RefreshRefused();
        }
        if (answer.status() < 200 || answer.status() >= 300) {
            throw new BungieUnavailableException("the Bungie token request answered " + answer.status());
        }
        if (json == null || !json.isObject() || text(json.path("access_token")) == null
                || text(json.path("refresh_token")) == null || id(json.path("membership_id")) == null) {
            throw garbled();
        }
        Instant now = clock.instant();
        long expiresIn = json.path("expires_in").asLong(3600);
        Duration refreshIn = json.path("refresh_expires_in").isNumber()
                ? Duration.ofSeconds(json.get("refresh_expires_in").asLong()) : REFRESH_LIFETIME;
        return new TokenGrant(json.get("access_token").asString(), now.plusSeconds(expiresIn),
                json.get("refresh_token").asString(), now.plus(refreshIn), id(json.path("membership_id")));
    }

    @Override
    public CurrentUser currentUser(String accessToken) {
        JsonNode response = object(get("/User/GetMembershipsForCurrentUser/", accessToken, MAX_BYTES));
        JsonNode user = response.path("bungieNetUser");
        String membershipId = id(user.path("membershipId"));
        if (membershipId == null || !response.path("destinyMemberships").isArray()) {
            throw garbled();
        }
        List<DestinyMembership> memberships = new ArrayList<>();
        for (JsonNode m : response.get("destinyMemberships")) {
            String id = id(m.path("membershipId"));
            if (id == null || !m.path("membershipType").isNumber()) {
                throw garbled();
            }
            memberships.add(new DestinyMembership(m.get("membershipType").asInt(), id,
                    m.path("crossSaveOverride").asInt(0)));
        }
        return new CurrentUser(membershipId, bungieName(user), memberships, id(response.path("primaryMembershipId")));
    }

    /** {@code Name#0042} from the global display name and its code, else the plain display name. */
    private static String bungieName(JsonNode user) {
        String name = text(user.path("cachedBungieGlobalDisplayName"));
        JsonNode code = user.path("cachedBungieGlobalDisplayNameCode");
        if (name != null && code.isNumber()) {
            return name + "#" + String.format("%04d", code.asInt());
        }
        return name != null ? name : text(user.path("displayName"));
    }

    // --- Destiny 2 ---

    @Override
    public Profile profile(int membershipType, String membershipId, String accessToken) {
        JsonNode response = object(get("/Destiny2/{type}/Profile/{id}/?components=100,200,204", accessToken,
                MAX_BYTES, membershipType, membershipId));
        JsonNode profile = response.path("profile");
        if (!profile.isObject()) {
            throw garbled();
        }
        boolean profilePrivate = withheld(profile);
        JsonNode data = profile.path("data");
        Instant lastPlayed = profilePrivate ? null : instant(data.path("dateLastPlayed"));
        Integer rank = profilePrivate || !data.path("currentGuardianRank").isNumber() ? null
                : data.get("currentGuardianRank").asInt();
        Long season = profilePrivate || !data.path("currentSeasonHash").isNumber() ? null
                : data.get("currentSeasonHash").asLong();

        JsonNode characters = response.path("characters");
        boolean charactersPrivate = characters.isObject() && withheld(characters);
        List<Character> list = new ArrayList<>();
        if (characters.isObject() && !charactersPrivate) {
            for (Map.Entry<String, JsonNode> entry : characters.path("data").properties()) {
                JsonNode c = entry.getValue();
                if (!c.path("classType").isNumber() || !c.path("light").isNumber()) {
                    throw garbled();
                }
                list.add(new Character(entry.getKey(), c.get("classType").asInt(), c.get("light").asInt(),
                        longValue(c.path("minutesPlayedTotal")), instant(c.path("dateLastPlayed")),
                        text(c.path("emblemPath"))));
            }
        }

        Activity activity = null;
        JsonNode activities = response.path("characterActivities");
        if (activities.isObject() && !withheld(activities)) {
            for (Map.Entry<String, JsonNode> entry : activities.path("data").properties()) {
                JsonNode a = entry.getValue();
                long hash = a.path("currentActivityHash").asLong(0);
                Instant started = instant(a.path("dateActivityStarted"));
                if (hash != 0 && (activity == null || (started != null
                        && (activity.startedAt() == null || started.isAfter(activity.startedAt()))))) {
                    activity = new Activity(entry.getKey(), hash, a.path("currentActivityModeHash").asLong(0),
                            started);
                }
            }
        }
        return new Profile(lastPlayed, rank, season, list, activity, profilePrivate, charactersPrivate);
    }

    @Override
    public Integer triumphScore(int membershipType, String membershipId, String accessToken) {
        JsonNode records = object(get("/Destiny2/{type}/Profile/{id}/?components=900", accessToken, MAX_RECORD_BYTES,
                membershipType, membershipId)).path("profileRecords");
        if (!records.isObject()) {
            throw garbled();
        }
        if (withheld(records)) {
            return null;
        }
        JsonNode score = records.path("data").path("activeScore");
        return score.isNumber() ? score.asInt() : null;
    }

    @Override
    public Definition definition(String entity, long hash, String locale) {
        JsonNode definition = object(get("/Destiny2/Manifest/{entity}/{hash}/?lc={lc}", null, MAX_BYTES, entity,
                Long.toUnsignedString(hash), locale));
        JsonNode display = definition.path("displayProperties");
        if (!display.isObject()) {
            throw garbled();
        }
        return new Definition(text(display.path("name")), text(display.path("icon")));
    }

    @Override
    public long currentSeasonHash() {
        JsonNode hash = object(get("/Settings/", null, MAX_BYTES)).path("destiny2CoreSettings")
                .path("currentSeasonHash");
        if (!hash.isNumber()) {
            throw garbled();
        }
        return hash.asLong();
    }

    @Override
    public Season season(long hash, String locale) {
        JsonNode definition = object(get("/Destiny2/Manifest/DestinySeasonDefinition/{hash}/?lc={lc}", null,
                MAX_BYTES, Long.toUnsignedString(hash), locale));
        if (!definition.path("seasonNumber").isNumber()) {
            throw garbled();
        }
        return new Season(definition.get("seasonNumber").asInt(), text(definition.path("displayProperties")
                .path("name")), instant(definition.path("startDate")), instant(definition.path("endDate")));
    }

    // --- HTTP ---

    /** The {@code Response} of a GET, the member's token in the header when there is one. */
    private JsonNode get(String path, String accessToken, int maxBytes, Object... vars) {
        Credentials app = app();
        charge();
        Answer answer;
        try {
            RestClient.RequestHeadersSpec<?> spec = restClient.get().uri(PLATFORM + path, vars)
                    .header("X-API-Key", app.apiKey())
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .header(HttpHeaders.USER_AGENT, USER_AGENT);
            if (accessToken != null) {
                spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
            }
            answer = spec.exchange((req, res) -> read(res.getStatusCode().value(),
                    res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER), res.getBody(), maxBytes));
        } catch (RestClientException e) {
            throw new BungieUnavailableException("the Bungie request failed");
        }
        return interpret(answer, accessToken != null);
    }

    private Answer read(int status, String retryAfter, InputStream body, int maxBytes) throws IOException {
        if (status >= 300 && status < 400) {
            return new Answer(status, retryAfter, null, false);
        }
        byte[] bytes = body == null ? new byte[0] : body.readNBytes(maxBytes + 1);
        return bytes.length > maxBytes ? new Answer(status, retryAfter, null, true)
                : new Answer(status, retryAfter, bytes, false);
    }

    private JsonNode interpret(Answer answer, boolean withToken) {
        int status = answer.status();
        if (status >= 300 && status < 400) {
            throw new BungieUnavailableException("Bungie answered a redirect (" + status + ")");
        }
        if (answer.tooBig()) {
            throw new BungieUnavailableException("the Bungie answer is too big");
        }
        JsonNode json = parseOrNull(answer);
        JsonNode throttle = json == null ? null : json.path("ThrottleSeconds");
        if (throttle != null && throttle.isNumber() && throttle.asLong() > 0) {
            limiter.pause(capped(Duration.ofSeconds(throttle.asLong())));
        }
        if (status == 429) {
            if (throttle == null || !throttle.isNumber() || throttle.asLong() <= 0) {
                limiter.pause(pause(answer.retryAfter()));
            }
            throw new BungieUnavailableException("Bungie rate limit hit (429)");
        }
        if (withToken && status == 401) {
            throw new BungieExceptions.AuthRefused();
        }
        if (json == null || !json.isObject() || !json.path("ErrorCode").isNumber()) {
            throw status >= 200 && status < 300 ? garbled()
                    : new BungieUnavailableException("Bungie answered " + status + " without a recognised body");
        }
        int code = json.get("ErrorCode").asInt();
        if (code == ACCOUNT_NOT_FOUND) {
            throw new BungieExceptions.NotFound();
        }
        if (code == PRIVACY_RESTRICTION) {
            throw new BungieExceptions.Private();
        }
        if (withToken && AUTH_REFUSED.contains(code)) {
            throw new BungieExceptions.AuthRefused();
        }
        if (code != SUCCESS || status < 200 || status >= 300) {
            throw new BungieUnavailableException("Bungie answered " + status + " with error code " + code);
        }
        JsonNode response = json.path("Response");
        if (!response.isObject()) {
            throw garbled();
        }
        return response;
    }

    private void charge() {
        if (!limiter.budget().tryAcquire()) {
            throw new BungieUnavailableException("the Bungie rate limit does not allow a request now");
        }
    }

    private Credentials app() {
        return credentials.current()
                .orElseThrow(() -> new BungieUnavailableException("no Bungie application is set"));
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

    private static Duration pause(String retryAfter) {
        try {
            long seconds = retryAfter == null ? 0 : Long.parseLong(retryAfter.trim());
            return seconds <= 0 ? PAUSE : capped(Duration.ofSeconds(seconds));
        } catch (NumberFormatException e) {
            return PAUSE;
        }
    }

    private static Duration capped(Duration pause) {
        return pause.compareTo(MAX_PAUSE) > 0 ? MAX_PAUSE : pause;
    }

    // --- mapping ---

    private static BungieUnavailableException garbled() {
        return new BungieUnavailableException("the Bungie answer does not have the expected shape");
    }

    private static JsonNode object(JsonNode node) {
        if (!node.isObject()) {
            throw garbled();
        }
        return node;
    }

    /** A component Bungie withholds: no {@code data}, {@code privacy} 2 (private). */
    private static boolean withheld(JsonNode component) {
        if (component.path("data").isObject()) {
            return false;
        }
        if (component.path("privacy").asInt(0) == 2) {
            return true;
        }
        throw garbled();
    }

    private static String form(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String text(JsonNode node) {
        return node.isString() && !node.asString().isBlank() ? node.asString() : null;
    }

    /** An id Bungie writes as a string (int64) or a number. */
    private static String id(JsonNode node) {
        if (node.isString() && node.asString().matches("[0-9]{1,20}")) {
            return node.asString();
        }
        return node.isIntegralNumber() ? node.asString() : null;
    }

    private static long longValue(JsonNode node) {
        if (node.isNumber()) {
            return node.asLong();
        }
        if (node.isString()) {
            try {
                return Long.parseLong(node.asString());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private static Instant instant(JsonNode node) {
        String value = text(node);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
