package fr.enimaloc.catapult.service.enrichment.rastats;

import fr.enimaloc.catapult.getter.retroachievements.RetroAchievementsApiKeyRotator;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * {@link RetroAchievementsClient} over {@code https://retroachievements.org/API}. The key only ever travels in the
 * {@code y} query parameter (the API has no header for it), never in a log or an exception message; redirects are
 * never followed, so the URL carrying it cannot leave the site. Every HTTP request is charged to the limiter.
 * Dates are read as {@code yyyy-MM-dd HH:mm:ss} UTC (the format is not verified on a real answer).
 */
@Slf4j
@Component
public class HttpRetroAchievementsClient implements RetroAchievementsClient {

    private static final String SITE = "https://retroachievements.org";
    private static final String API = SITE + "/API/";
    private static final String BADGES = "https://media.retroachievements.org/Badge/";
    private static final Duration PAUSE = Duration.ofSeconds(60);
    private static final Duration REJECTED_FOR = Duration.ofHours(1);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private record Answer(HttpStatusCode status, String retryAfter, String body) {
    }

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RestClient restClient;
    private final RetroAchievementsRateLimiter limiter;
    private final Optional<RetroAchievementsApiKeyRotator> rotator;

    @Autowired
    public HttpRetroAchievementsClient(RestClient.Builder builder, RetroAchievementsRateLimiter limiter,
                                       Optional<RetroAchievementsApiKeyRotator> rotator,
                                       @Value("${app.base-url}") String baseUrl) {
        this(builder.requestFactory(requestFactory())
                .defaultHeader(HttpHeaders.USER_AGENT, "Catapult/1.0 (+" + baseUrl + ")").build(), limiter, rotator);
    }

    HttpRetroAchievementsClient(RestClient restClient, RetroAchievementsRateLimiter limiter,
                                Optional<RetroAchievementsApiKeyRotator> rotator) {
        this.restClient = restClient;
        this.limiter = limiter;
        this.rotator = rotator;
    }

    /** Timeouts and buffering as the shared builder's, and a redirect is never followed (the URL holds the key). */
    static ClientHttpRequestFactory requestFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        return new BufferingClientHttpRequestFactory(factory);
    }

    @Override
    public Optional<RaUser> profile(String userOrUlid, RaCredentials credentials) {
        Optional<JsonNode> answer = get("API_GetUserProfile.php?u={u}&y={y}", credentials, userOrUlid,
                credentials.key());
        return answer.filter(json -> json.hasNonNull("ULID") && json.hasNonNull("User")).map(json -> new RaUser(
                json.get("User").asString(), json.get("ULID").asString(), site(text(json.path("UserPic"))),
                integer(json.path("TotalPoints"), 0), integer(json.path("TotalSoftcorePoints"), 0),
                integer(json.path("TotalTruePoints"), 0), text(json.path("RichPresenceMsg")),
                integer(json.path("LastGameID"), null), integer(json.path("Untracked"), 0) != 0));
    }

    @Override
    public List<RaRecentGame> recentlyPlayed(String ulid, int count, RaCredentials credentials) {
        List<RaRecentGame> games = new ArrayList<>();
        for (JsonNode node : array(get("API_GetUserRecentlyPlayedGames.php?u={u}&c={c}&y={y}", credentials, ulid,
                count, credentials.key()))) {
            if (node.hasNonNull("GameID")) {
                games.add(new RaRecentGame(integer(node.path("GameID"), 0), text(node.path("ConsoleName")),
                        text(node.path("Title")), site(text(node.path("ImageIcon"))), date(node.path("LastPlayed")),
                        integer(node.path("AchievementsTotal"), 0), integer(node.path("NumAchieved"), 0),
                        integer(node.path("NumAchievedHardcore"), 0), integer(node.path("ScoreAchieved"), 0),
                        integer(node.path("ScoreAchievedHardcore"), 0), integer(node.path("PossibleScore"), 0)));
            }
        }
        return games;
    }

    @Override
    public Optional<RaGameProgress> progress(int gameId, String ulid, RaCredentials credentials) {
        Optional<JsonNode> answer = get("API_GetGameInfoAndUserProgress.php?g={g}&u={u}&a=1&y={y}", credentials,
                gameId, ulid, credentials.key());
        return answer.filter(json -> json.hasNonNull("ID")).map(json -> {
            List<RaAchievement> achievements = new ArrayList<>();
            for (JsonNode node : json.path("Achievements")) {
                achievements.add(achievement(node));
            }
            achievements.sort(Comparator.comparing(RaAchievement::displayOrder,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            return new RaGameProgress(integer(json.path("ID"), 0), text(json.path("Title")),
                    text(json.path("ConsoleName")), site(text(json.path("ImageIcon"))),
                    integer(json.path("NumAchievements"), 0), integer(json.path("NumDistinctPlayers"), 0),
                    integer(json.path("NumDistinctPlayersHardcore"), 0), integer(json.path("NumAwardedToUser"), 0),
                    integer(json.path("NumAwardedToUserHardcore"), 0), text(json.path("UserCompletion")),
                    text(json.path("UserCompletionHardcore")), integer(json.path("UserTotalPlaytime"), null),
                    text(json.path("HighestAwardKind")), isoDate(json.path("HighestAwardDate")),
                    List.copyOf(achievements));
        });
    }

    @Override
    public List<RaAchievement> recentAchievements(String ulid, int minutes, RaCredentials credentials) {
        List<RaAchievement> recent = new ArrayList<>();
        for (JsonNode node : array(get("API_GetUserRecentAchievements.php?u={u}&m={m}&y={y}", credentials, ulid,
                minutes, credentials.key()))) {
            Instant at = date(node.path("Date"));
            recent.add(new RaAchievement(integer(node.path("AchievementID"), 0), text(node.path("Title")),
                    text(node.path("Description")), integer(node.path("Points"), 0),
                    integer(node.path("TrueRatio"), 0), badge(node), null, text(node.path("Type")), null, null, at,
                    integer(node.path("HardcoreMode"), 0) != 0 ? at : null, integer(node.path("GameID"), null),
                    text(node.path("GameTitle"))));
        }
        return recent;
    }

    // --- HTTP ---

    /**
     * GET, parsed; empty on 404. A 401 is a rejected key, a 429 pauses the limiter, everything else that is not a
     * readable 2xx (3xx included) is {@link RaUnavailableException}.
     */
    private Optional<JsonNode> get(String template, RaCredentials credentials, Object... variables) {
        if (limiter.paused()) {
            throw new RaUnavailableException("RetroAchievements is paused after a rate limit answer");
        }
        FetchBudget budget = limiter.budget();
        if (!budget.tryAcquire()) {
            throw new UpstreamBudgetExceededException(budget.retryAfter());
        }
        if (!credentials.personal()) {
            rotator.ifPresent(r -> r.onCall(credentials.key()));
        }
        Answer answer;
        try {
            answer = restClient.get().uri(API + template, variables).header(HttpHeaders.ACCEPT, "application/json")
                    .exchange((req, res) -> new Answer(res.getStatusCode(), res.getHeaders().getFirst(
                            HttpHeaders.RETRY_AFTER), res.getStatusCode().is2xxSuccessful()
                            ? res.bodyTo(String.class) : null));
        } catch (RestClientException e) {
            // The message of a transport failure names the URL, and so the key: it is not kept.
            log.warn("RetroAchievements call failed: {}", e.getClass().getSimpleName());
            throw new RaUnavailableException("RetroAchievements cannot be reached");
        }
        int status = answer.status().value();
        if (status == 401) {
            if (!credentials.personal()) {
                rotator.ifPresent(r -> r.onKeyRejected(credentials.key(), REJECTED_FOR));
            }
            throw new RaKeyRejectedException();
        }
        if (status == 429) {
            limiter.pause(retryAfter(answer.retryAfter()));
            throw new RaUnavailableException("RetroAchievements rate limit hit (429)");
        }
        if (status == 404) {
            return Optional.empty();
        }
        if (!answer.status().is2xxSuccessful()) {
            throw new RaUnavailableException("RetroAchievements answered " + status);
        }
        return Optional.of(parse(answer.body()));
    }

    private static Duration retryAfter(String header) {
        try {
            long seconds = header == null ? 0 : Long.parseLong(header.strip());
            return seconds > 0 ? Duration.ofSeconds(seconds) : PAUSE;
        } catch (NumberFormatException e) {
            return PAUSE;
        }
    }

    /** The body as JSON; a blank or unreadable one is an outage (an error page, a cut answer), never "absent". */
    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            throw new RaUnavailableException("RetroAchievements answered an empty body");
        }
        try {
            return mapper.readTree(body);
        } catch (JacksonException e) {
            log.warn("RetroAchievements answer is not JSON: {}", e.getOriginalMessage());
            throw new RaUnavailableException("RetroAchievements answered something that is not JSON");
        }
    }

    // --- mapping ---

    private JsonNode array(Optional<JsonNode> answer) {
        return answer.filter(JsonNode::isArray).orElseGet(mapper::createArrayNode);
    }

    private static RaAchievement achievement(JsonNode node) {
        Instant earned = date(node.path("DateEarned"));
        return new RaAchievement(integer(node.path("ID"), 0), text(node.path("Title")),
                text(node.path("Description")), integer(node.path("Points"), 0), integer(node.path("TrueRatio"), 0),
                badge(node), integer(node.path("DisplayOrder"), null), text(node.path("type")),
                integer(node.path("NumAwarded"), null), integer(node.path("NumAwardedHardcore"), null), earned,
                date(node.path("DateEarnedHardcore")), null, null);
    }

    private static String badge(JsonNode node) {
        String name = text(node.path("BadgeName"));
        return name == null ? null : BADGES + name + ".png";
    }

    /** An absolute URL on the site from a site-relative path. */
    private static String site(String path) {
        return path == null ? null : path.startsWith("/") ? SITE + path : path;
    }

    private static String text(JsonNode node) {
        return node.isString() && !node.asString().isBlank() ? node.asString() : null;
    }

    /** A number, which the API sometimes sends as a string. */
    private static Integer integer(JsonNode node, Integer fallback) {
        if (node.isNumber()) {
            return node.intValue();
        }
        if (node.isString()) {
            try {
                return Integer.parseInt(node.asString().strip());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    private static Instant date(JsonNode node) {
        try {
            return node.isString() ? LocalDateTime.parse(node.asString().strip(), DATE).toInstant(ZoneOffset.UTC)
                    : null;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Instant isoDate(JsonNode node) {
        try {
            return node.isString() ? java.time.OffsetDateTime.parse(node.asString().strip()).toInstant() : null;
        } catch (DateTimeParseException e) {
            return date(node);
        }
    }
}
