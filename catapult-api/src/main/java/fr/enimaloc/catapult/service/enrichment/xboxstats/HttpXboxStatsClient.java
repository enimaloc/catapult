package fr.enimaloc.catapult.service.enrichment.xboxstats;

import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Achievement;
import fr.enimaloc.catapult.service.xbox.XboxUserTokenService.XstsSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link XboxStatsClient} over Xbox Live ({@code titlehub}, {@code achievements}, {@code userstats}). The session
 * token travels in {@code Authorization: XBL3.0 x=<uhs>;<token>} and is never logged. The transport never follows
 * a redirect; every request is charged to the member's {@link FetchBudget} of the {@link XboxStatsRateLimiter}.
 * A refusal of the budget, a 3xx, a 429 (which also pauses the member), a 5xx or an unreadable body is an
 * {@link XboxStatsUnavailableException}; 401 and 403 are an {@link XboxStatsForbiddenException}.
 */
@Slf4j
@Component
public class HttpXboxStatsClient implements XboxStatsClient {

    private static final String TITLEHUB = "https://titlehub.xboxlive.com/users/xuid({xuid})/titles/titlehistory"
            + "/decoration/achievement,detail?maxItems={max}";
    private static final String ACHIEVEMENTS = "https://achievements.xboxlive.com/users/xuid({xuid})/achievements"
            + "?titleId={titleId}&maxItems=1000";
    private static final String USERSTATS = "https://userstats.xboxlive.com/batch";
    private static final String USER_AGENT = "Catapult/1.0 (+https://catapult.enimaloc.fr)";
    private static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(60);

    private record Answer(HttpStatusCode status, String retryAfter, String body) {
    }

    private final JsonMapper mapper = JsonMapper.builder().build();
    /** The usual region of a language sent without one. */
    private static final Map<String, String> DEFAULT_REGIONS = Map.ofEntries(Map.entry("en", "US"),
            Map.entry("fr", "FR"), Map.entry("de", "DE"), Map.entry("es", "ES"), Map.entry("it", "IT"),
            Map.entry("pt", "BR"), Map.entry("nl", "NL"), Map.entry("pl", "PL"), Map.entry("ru", "RU"),
            Map.entry("ja", "JP"), Map.entry("ko", "KR"), Map.entry("zh", "CN"));

    private final RestClient restClient;
    private final XboxStatsRateLimiter limiter;

    @Autowired
    public HttpXboxStatsClient(RestClient.Builder builder, XboxStatsRateLimiter limiter) {
        this(builder.requestFactory(requestFactory()).build(), limiter);
    }

    HttpXboxStatsClient(RestClient restClient, XboxStatsRateLimiter limiter) {
        this.restClient = restClient;
        this.limiter = limiter;
    }

    /** The transport: no redirect followed (the session token is in a header), timeouts, buffered bodies. */
    static ClientHttpRequestFactory requestFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        return new BufferingClientHttpRequestFactory(factory);
    }

    @Override
    public List<XboxTitle> titleHistory(UUID userId, XstsSession session, int max, Locale locale) {
        Answer answer = send(userId, session, locale, TITLEHUB, session.xuid(), max);
        JsonNode titles = json(requireSuccess(answer)).path("titles");
        if (!titles.isArray()) {
            throw new XboxStatsUnavailableException("Xbox answered no titles array");
        }
        List<XboxTitle> result = new ArrayList<>();
        for (JsonNode title : titles) {
            if (!title.hasNonNull("titleId")) {
                continue;
            }
            JsonNode achievement = title.path("achievement");
            result.add(new XboxTitle(title.get("titleId").asString(), text(title.path("name")),
                    productIds(title.path("detail").path("availabilities")), text(title.path("type")),
                    integer(achievement.path("currentAchievements")), integer(achievement.path("totalAchievements")),
                    integer(achievement.path("currentGamerscore")), integer(achievement.path("totalGamerscore")),
                    instant(title.path("titleHistory").path("lastTimePlayed"))));
        }
        return List.copyOf(result);
    }

    @Override
    public List<Achievement> achievements(UUID userId, XstsSession session, String titleId, Locale locale) {
        Answer answer = send(userId, session, locale, ACHIEVEMENTS, session.xuid(), titleId);
        failOnRefusal(answer);
        JsonNode achievements = json(requireSuccess(answer)).path("achievements");
        if (!achievements.isArray()) {
            throw new XboxStatsUnavailableException("Xbox answered no achievements array");
        }
        List<Achievement> result = new ArrayList<>();
        for (JsonNode node : achievements) {
            if (!node.hasNonNull("id")) {
                continue;
            }
            boolean unlocked = "Achieved".equalsIgnoreCase(node.path("progressState").asString(""));
            boolean secret = node.path("isSecret").asBoolean(false);
            boolean hidden = secret && !unlocked;
            Instant at = unlocked ? instant(node.path("progression").path("timeUnlocked")) : null;
            JsonNode percentage = node.path("rarity").path("currentPercentage");
            result.add(new Achievement(node.get("id").asString(), hidden ? null : text(node.path("name")),
                    hidden ? null : text(node.path("description")), icon(node.path("mediaAssets")), unlocked,
                    at != null && at.getEpochSecond() > 0 ? at : null,
                    percentage.isNumber() ? percentage.decimalValue() : null, gamerscore(node.path("rewards")),
                    secret));
        }
        return List.copyOf(result);
    }

    @Override
    public Optional<Long> minutesPlayed(UUID userId, XstsSession session, String titleId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("arrangebyfield", "xuid");
        body.put("xuids", List.of(session.xuid()));
        body.put("stats", List.of(Map.of("name", "MinutesPlayed", "titleid", titleId)));
        // a number: the language does not matter
        Answer answer = send(userId, session, null, USERSTATS, body);
        failOnRefusal(answer);
        JsonNode root = json(requireSuccess(answer));
        if (!root.path("statlistscollection").isArray()) {
            throw new XboxStatsUnavailableException("Xbox answered no statlistscollection array");
        }
        for (JsonNode collection : root.path("statlistscollection")) {
            for (JsonNode stat : collection.path("stats")) {
                if ("MinutesPlayed".equals(stat.path("name").asString("")) && stat.path("value").isNumber()) {
                    return Optional.of(stat.get("value").asLong());
                }
            }
        }
        return Optional.empty();
    }

    // --- HTTP ---

    /**
     * One request under the member's session, in {@code locale}: a GET of {@code template} (its variables in
     * {@code args}), or, when {@code args} is a single map, a POST of it as JSON.
     */
    private Answer send(UUID userId, XstsSession session, Locale locale, String template, Object... args) {
        FetchBudget budget = limiter.forMember(userId);
        if (!budget.tryAcquire()) {
            throw new XboxStatsUnavailableException("Xbox rate budget reached");
        }
        Answer answer;
        try {
            RestClient.RequestHeadersSpec<?> request;
            if (args.length == 1 && args[0] instanceof Map<?, ?> body) {
                request = restClient.post().uri(template).contentType(MediaType.APPLICATION_JSON).body(body);
            } else {
                request = restClient.get().uri(template, args);
            }
            answer = request.header(HttpHeaders.AUTHORIZATION, "XBL3.0 x=" + session.userHash() + ";" + session.token())
                    .header("x-xbl-contract-version", "2")
                    .header(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage(locale))
                    .header(HttpHeaders.ACCEPT, "application/json")
                    .header(HttpHeaders.USER_AGENT, USER_AGENT)
                    .exchange((req, res) -> new Answer(res.getStatusCode(),
                            res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER),
                            res.getStatusCode().is3xxRedirection() ? null : res.bodyTo(String.class)));
        } catch (RuntimeException e) {
            throw new XboxStatsUnavailableException("Xbox request failed: " + e.getClass().getSimpleName());
        }
        if (answer.status().value() == 429) {
            limiter.pause(userId, retryAfter(answer.retryAfter()));
            throw new XboxStatsUnavailableException("Xbox rate limit hit (429)");
        }
        return answer;
    }

    /**
     * {@code locale} as a language tag with a region (Titlehub answers 400 without one): given by the caller, never
     * read from the thread (the reads run on worker threads). A language alone gets its usual region when it has a
     * known one; en-US otherwise.
     */
    static String acceptLanguage(Locale locale) {
        if (locale == null || locale.getLanguage().isEmpty()) {
            return "en-US";
        }
        if (!locale.getCountry().isEmpty()) {
            return locale.toLanguageTag();
        }
        String region = DEFAULT_REGIONS.get(locale.getLanguage());
        return region == null ? "en-US" : locale.getLanguage() + "-" + region;
    }

    private static Duration retryAfter(String header) {
        try {
            long seconds = header != null ? Long.parseLong(header.trim()) : DEFAULT_RETRY_AFTER.toSeconds();
            return seconds > 0 ? Duration.ofSeconds(seconds) : DEFAULT_RETRY_AFTER;
        } catch (NumberFormatException e) {
            return DEFAULT_RETRY_AFTER;
        }
    }

    private static void failOnRefusal(Answer answer) {
        int status = answer.status().value();
        if (status == 401 || status == 403) {
            throw new XboxStatsForbiddenException("Xbox answered " + status, status);
        }
    }

    /** The body of a 2xx answer; any other status (a 3xx, a 4xx, a 5xx incl. 503) is an outage. */
    private static String requireSuccess(Answer answer) {
        if (!answer.status().is2xxSuccessful()) {
            throw new XboxStatsUnavailableException("Xbox answered " + answer.status().value());
        }
        return answer.body();
    }

    /** The body as a JSON object; a blank or unreadable one is an outage, never "absent". */
    private JsonNode json(String body) {
        if (body == null || body.isBlank()) {
            throw new XboxStatsUnavailableException("Xbox answered an empty body");
        }
        try {
            JsonNode node = mapper.readTree(body);
            if (!node.isObject()) {
                throw new XboxStatsUnavailableException("Xbox answered something that is not an object");
            }
            return node;
        } catch (JacksonException e) {
            throw new XboxStatsUnavailableException("Xbox answered something that is not JSON");
        }
    }

    private static List<String> productIds(JsonNode availabilities) {
        List<String> ids = new ArrayList<>();
        for (JsonNode availability : availabilities) {
            for (String field : List.of("AvailabilityId", "ProductId")) {
                String id = text(availability.path(field));
                if (id != null && !ids.contains(id)) {
                    ids.add(id);
                }
            }
        }
        return List.copyOf(ids);
    }

    private static String icon(JsonNode assets) {
        for (JsonNode asset : assets) {
            if ("Icon".equalsIgnoreCase(asset.path("type").asString("")) && text(asset.path("url")) != null) {
                return asset.get("url").asString();
            }
        }
        return null;
    }

    private static Integer gamerscore(JsonNode rewards) {
        for (JsonNode reward : rewards) {
            if ("Gamerscore".equalsIgnoreCase(reward.path("type").asString(""))) {
                try {
                    return Integer.valueOf(reward.path("value").asString("").trim());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static Integer integer(JsonNode node) {
        return node.isNumber() ? node.asInt() : null;
    }

    private static Instant instant(JsonNode node) {
        if (!node.isString()) {
            return null;
        }
        try {
            return Instant.parse(node.asString());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String text(JsonNode node) {
        return node.isString() && !node.asString().isBlank() ? node.asString() : null;
    }
}
