package fr.enimaloc.catapult.service.enrichment.steamstats;

import fr.enimaloc.catapult.service.enrichment.EnrichmentUserAgent;
import fr.enimaloc.catapult.getter.steam.SteamApiKeyRotator;
import fr.enimaloc.catapult.getter.steam.SteamRateLimiter;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link SteamStatsClient} over {@code https://api.steampowered.com}. The key travels in {@code ?key=} (masked by
 * the HTTP trace and the debug redactor) and is never logged. The transport never follows a redirect: the key
 * must not leave the API. Every request asks the {@link SteamRateLimiter} without waiting; a refusal, a missing
 * key, a 429, a 3xx, a 5xx or an unreadable body is a {@link SteamStatsUnavailableException}.
 */
@Slf4j
@Component
public class HttpSteamStatsClient implements SteamStatsClient {

    private static final String API = "https://api.steampowered.com";
    private static final String STATS = API + "/ISteamUserStats";
    private static final int DEFAULT_RETRY_AFTER_SECONDS = 60;

    private record Answer(HttpStatusCode status, String retryAfter, String body) {
    }

    private record Key(String value, boolean pooled) {
    }

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RestClient restClient;
    private final Optional<SteamApiKeyRotator> rotator;
    private final Optional<SteamRateLimiter> rateLimiter;

    @Autowired
    public HttpSteamStatsClient(RestClient.Builder builder, Optional<SteamApiKeyRotator> rotator,
                                Optional<SteamRateLimiter> rateLimiter) {
        this(builder.requestFactory(requestFactory()).build(), rotator, rateLimiter);
    }

    HttpSteamStatsClient(RestClient restClient, Optional<SteamApiKeyRotator> rotator,
                         Optional<SteamRateLimiter> rateLimiter) {
        this.restClient = restClient;
        this.rotator = rotator;
        this.rateLimiter = rateLimiter;
    }

    /** The transport of this client: no redirect followed (the key is in the URL), timeouts, buffered bodies. */
    static ClientHttpRequestFactory requestFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        return new BufferingClientHttpRequestFactory(factory);
    }

    @Override
    public boolean usable(String personalKeyOrNull) {
        return rateLimiter.isPresent() && (personalKeyOrNull != null && !personalKeyOrNull.isBlank()
                || rotator.map(SteamApiKeyRotator::hasKeys).orElse(false));
    }

    @Override
    public Optional<SteamLibrary> library(String steamId, String personalKeyOrNull) {
        Answer answer = get(personalKeyOrNull, API + "/IPlayerService/GetOwnedGames/v1/?key={key}&steamid={id}"
                + "&include_appinfo=0&include_played_free_games=1", steamId);
        JsonNode response = json(requireSuccess(answer)).path("response");
        if (!response.isObject()) {
            throw new SteamStatsUnavailableException("Steam answered no response object");
        }
        if (!response.has("game_count")) {
            return Optional.empty();
        }
        Map<String, SteamLibrary.Entry> games = new LinkedHashMap<>();
        for (JsonNode game : response.path("games")) {
            if (!game.hasNonNull("appid")) {
                continue;
            }
            long lastPlayed = game.path("rtime_last_played").asLong(0);
            games.put(game.get("appid").asString(), new SteamLibrary.Entry(game.path("playtime_forever").asLong(0),
                    game.path("playtime_2weeks").asLong(0), lastPlayed > 0 ? Instant.ofEpochSecond(lastPlayed) : null));
        }
        return Optional.of(new SteamLibrary(games));
    }

    @Override
    public SteamPlayerAchievements playerAchievements(String steamId, String appId, String lang,
                                                      String personalKeyOrNull) {
        Answer answer = get(personalKeyOrNull, STATS + "/GetPlayerAchievements/v1/?key={key}&steamid={id}"
                + "&appid={app}&l={lang}", steamId, appId, lang);
        int status = answer.status().value();
        if (status == 400 || status == 403) {
            return refusal(answer.body()).orElseThrow(
                    () -> new SteamStatsUnavailableException("Steam answered " + status));
        }
        JsonNode stats = json(requireSuccess(answer)).path("playerstats");
        if (!stats.isObject()) {
            throw new SteamStatsUnavailableException("Steam answered no playerstats object");
        }
        if (!stats.path("success").asBoolean(true)) {
            return refusal(stats.path("error").asString("")).orElseThrow(
                    () -> new SteamStatsUnavailableException("Steam reported a failure"));
        }
        if (!stats.path("achievements").isArray()) {
            return SteamPlayerAchievements.of(Status.NO_ACHIEVEMENTS);
        }
        Map<String, Instant> unlocked = new HashMap<>();
        for (JsonNode achievement : stats.get("achievements")) {
            if (achievement.path("achieved").asInt(0) == 1 && achievement.hasNonNull("apiname")) {
                long at = achievement.path("unlocktime").asLong(0);
                unlocked.put(achievement.get("apiname").asString(), at > 0 ? Instant.ofEpochSecond(at) : null);
            }
        }
        return new SteamPlayerAchievements(Status.OK, unlocked);
    }

    @Override
    public Optional<SteamAchievementSchema> schema(String appId, String lang) {
        Answer answer = get(null, STATS + "/GetSchemaForGame/v2/?key={key}&appid={app}&l={lang}", appId, lang);
        JsonNode game = json(requireSuccess(answer)).path("game");
        if (!game.isObject()) {
            throw new SteamStatsUnavailableException("Steam answered no game object");
        }
        JsonNode achievements = game.path("availableGameStats").path("achievements");
        if (!achievements.isArray()) {
            return Optional.empty();
        }
        List<SteamAchievementSchema.Definition> definitions = new ArrayList<>();
        for (JsonNode node : achievements) {
            if (!node.hasNonNull("name")) {
                continue;
            }
            definitions.add(new SteamAchievementSchema.Definition(node.get("name").asString(),
                    text(node.path("displayName")), text(node.path("description")), text(node.path("icon")),
                    text(node.path("icongray")), node.path("hidden").asInt(0) == 1));
        }
        return Optional.of(new SteamAchievementSchema(List.copyOf(definitions)));
    }

    @Override
    public Map<String, BigDecimal> globalPercentages(String appId) {
        Answer answer = get(null, STATS + "/GetGlobalAchievementPercentagesForApp/v0002/?gameid={app}"
                + "&format=json&key={key}", appId);
        JsonNode root = json(requireSuccess(answer)).path("achievementpercentages");
        if (!root.isObject()) {
            throw new SteamStatsUnavailableException("Steam answered no percentages object");
        }
        Map<String, BigDecimal> percentages = new LinkedHashMap<>();
        for (JsonNode entry : root.path("achievements")) {
            String percent = entry.path("percent").asString("");
            if (!entry.hasNonNull("name") || percent.isBlank()) {
                continue;
            }
            try {
                percentages.put(entry.get("name").asString(), new BigDecimal(percent.trim()));
            } catch (NumberFormatException e) {
                throw new SteamStatsUnavailableException("Steam answered an unreadable percentage");
            }
        }
        return percentages;
    }

    // --- HTTP ---

    /**
     * One GET under the personal key or else the pool's. {@code template} has a {@code {key}} variable first
     * among its URL variables: {@code variables} are the other ones, in order of appearance. A personal key Steam
     * refuses (401, or a 403 that is not a refusal of the member's details) is replaced by a pool key, once.
     */
    private Answer get(String personalKeyOrNull, String template, Object... variables) {
        Key key = pick(personalKeyOrNull);
        Answer answer = call(key, template, variables);
        if (!key.pooled() && keyRefused(answer)) {
            // the key itself is never logged
            log.debug("Steam refused a personal key ({}); asking with the pool", answer.status().value());
            return call(pick(null), template, variables);
        }
        return answer;
    }

    /** Whether Steam refused the key itself, as opposed to the member's private details. */
    private static boolean keyRefused(Answer answer) {
        int status = answer.status().value();
        return status == 401 || status == 403 && refusal(answer.body()).isEmpty();
    }

    private Answer call(Key key, String template, Object... variables) {
        if (!rateLimiter.map(limiter -> limiter.tryAcquireNow(key.value())).orElse(false)) {
            throw new SteamStatsUnavailableException("Steam rate budget reached");
        }
        Object[] all = withKey(template, key.value(), variables);
        Answer answer;
        try {
            answer = restClient.get().uri(template, all).header(HttpHeaders.ACCEPT, "application/json")
                    .header(HttpHeaders.USER_AGENT, EnrichmentUserAgent.VALUE)
                    .exchange((req, res) -> new Answer(res.getStatusCode(),
                            res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER),
                            res.getStatusCode().is3xxRedirection() ? null : res.bodyTo(String.class)));
        } catch (RuntimeException e) {
            throw new SteamStatsUnavailableException("Steam request failed: " + e.getClass().getSimpleName());
        }
        if (answer.status().value() == 429) {
            int retryAfter = retryAfter(answer.retryAfter());
            if (key.pooled()) {
                rotator.ifPresent(r -> r.onKeyRateLimited(key.value(), retryAfter));
            } else {
                rateLimiter.ifPresent(l -> l.onRateLimitResponse(key.value(), retryAfter));
            }
            throw new SteamStatsUnavailableException("Steam rate limit hit (429)");
        }
        return answer;
    }

    /** The values of the template's variables in order: the key replaces {@code {key}} wherever it stands. */
    private static Object[] withKey(String template, String key, Object[] variables) {
        List<Object> values = new ArrayList<>();
        int next = 0;
        int from = 0;
        while (true) {
            int open = template.indexOf('{', from);
            if (open < 0) {
                break;
            }
            int close = template.indexOf('}', open);
            values.add(template.substring(open + 1, close).equals("key") ? key : variables[next++]);
            from = close + 1;
        }
        return values.toArray();
    }

    private Key pick(String personalKeyOrNull) {
        if (personalKeyOrNull != null && !personalKeyOrNull.isBlank()) {
            return new Key(personalKeyOrNull, false);
        }
        return rotator.flatMap(SteamApiKeyRotator::nextKey).map(value -> new Key(value, true))
                .orElseThrow(() -> new SteamStatsUnavailableException("No Steam API key available"));
    }

    private static int retryAfter(String header) {
        try {
            return header != null ? Integer.parseInt(header.trim()) : DEFAULT_RETRY_AFTER_SECONDS;
        } catch (NumberFormatException e) {
            return DEFAULT_RETRY_AFTER_SECONDS;
        }
    }

    /** The body of a 2xx answer; any other status (a 3xx, a 4xx, a 5xx) is an outage. */
    private static String requireSuccess(Answer answer) {
        if (!answer.status().is2xxSuccessful()) {
            throw new SteamStatsUnavailableException("Steam answered " + answer.status().value());
        }
        return answer.body();
    }

    /** The body as JSON; a blank or unreadable one is an outage (an error page, a cut answer), never "absent". */
    private JsonNode json(String body) {
        if (body == null || body.isBlank()) {
            throw new SteamStatsUnavailableException("Steam answered an empty body");
        }
        try {
            JsonNode node = mapper.readTree(body);
            if (!node.isObject()) {
                throw new SteamStatsUnavailableException("Steam answered something that is not an object");
            }
            return node;
        } catch (JacksonException e) {
            throw new SteamStatsUnavailableException("Steam answered something that is not JSON");
        }
    }

    /** What a refusal of {@code GetPlayerAchievements} says: private details, an app without stats, or neither. */
    private static Optional<SteamPlayerAchievements> refusal(String text) {
        String lower = text == null ? "" : text.toLowerCase();
        if (lower.contains("not public")) {
            return Optional.of(SteamPlayerAchievements.of(Status.PRIVATE));
        }
        if (lower.contains("no stats")) {
            return Optional.of(SteamPlayerAchievements.of(Status.NO_ACHIEVEMENTS));
        }
        return Optional.empty();
    }

    private static String text(JsonNode node) {
        return node.isString() && !node.asString().isBlank() ? node.asString() : null;
    }
}
