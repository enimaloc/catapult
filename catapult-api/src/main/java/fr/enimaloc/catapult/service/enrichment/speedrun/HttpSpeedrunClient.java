package fr.enimaloc.catapult.service.enrichment.speedrun;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@link SpeedrunClient} over {@code https://www.speedrun.com/api/v1}. The personal key only ever travels in the
 * {@code X-API-Key} header, never in a URL, and is never logged. A name lookup of an account answers with a
 * redirect to its id, followed here (within the API only).
 */
@Slf4j
@Component
public class HttpSpeedrunClient implements SpeedrunClient {

    private static final String SITE = "https://www.speedrun.com";
    private static final String BASE = SITE + "/api/v1";
    private static final String API_PATH = "/api/v1/";
    private static final Duration PAUSE = Duration.ofSeconds(60);
    private static final int MAX_REDIRECTS = 2;
    private static final String[] TIMINGS = {"realtime", "realtime_noloads", "ingame"};

    private record Answer(HttpStatusCode status, String location, String body) {
    }

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RestClient restClient;
    private final SpeedrunRateLimiter limiter;

    public HttpSpeedrunClient(RestClient.Builder builder, SpeedrunRateLimiter limiter) {
        this.restClient = builder.build();
        this.limiter = limiter;
    }

    @Override
    public List<SpeedrunGame> searchGames(String name) {
        List<SpeedrunGame> games = new ArrayList<>();
        for (JsonNode node : array(get(BASE + "/games?name={name}&max=20", null, false, name))) {
            game(node).ifPresent(games::add);
        }
        return games;
    }

    @Override
    public Optional<SpeedrunRecords> records(String gameId) {
        Optional<JsonNode> answer = get(BASE + "/games/{id}/records?scope=full-game&miscellaneous=no&top=1"
                + "&skip-empty=true&embed=game,category,players&max=20", null, false, gameId);
        if (answer.isEmpty()) {
            return Optional.empty();
        }
        JsonNode entries = array(answer);
        SpeedrunGame game = null;
        List<SpeedrunRecords.CategoryRecord> categories = new ArrayList<>();
        for (JsonNode entry : entries) {
            if (game == null) {
                game = game(entry.path("game").path("data")).orElse(null);
            }
            category(entry, game == null ? null : game.id(), defaultTiming(entry.path("game").path("data")))
                    .ifPresent(categories::add);
        }
        if (game == null) {
            game = get(BASE + "/games/{id}", null, false, gameId).flatMap(json -> game(json.path("data")))
                    .orElse(null);
        }
        return Optional.ofNullable(game).map(g -> new SpeedrunRecords(g, List.copyOf(categories)));
    }

    @Override
    public List<SpeedrunUser> usersByTwitch(String login) {
        List<SpeedrunUser> users = new ArrayList<>();
        for (JsonNode node : array(get(BASE + "/users?twitch={login}&max=20", null, false, login))) {
            user(node).ifPresent(users::add);
        }
        return users;
    }

    @Override
    public Optional<SpeedrunUser> user(String nameOrId) {
        return get(BASE + "/users/{id}", null, false, nameOrId).flatMap(json -> user(json.path("data")));
    }

    @Override
    public Optional<SpeedrunUser> profile(String apiKey) {
        return get(BASE + "/profile", apiKey, true).flatMap(json -> user(json.path("data")));
    }

    @Override
    public List<SpeedrunRun> personalBests(String userId, String gameIdOrNull) {
        Optional<JsonNode> answer = gameIdOrNull == null
                ? get(BASE + "/users/{id}/personal-bests?embed=game,category", null, false, userId)
                : get(BASE + "/users/{id}/personal-bests?embed=game,category&game={game}", null, false, userId,
                        gameIdOrNull);
        List<SpeedrunRun> runs = new ArrayList<>();
        for (JsonNode entry : array(answer)) {
            run(entry.path("run"), entry.path("game"), entry.path("category"),
                    entry.hasNonNull("place") ? entry.get("place").asInt() : null).ifPresent(runs::add);
        }
        return runs;
    }

    @Override
    public List<SpeedrunRun> pendingRuns(String userId, String gameIdOrNull, String apiKeyOrNull) {
        Optional<JsonNode> answer = gameIdOrNull == null
                ? get(BASE + "/runs?user={user}&status=new&embed=game,category&max=50", apiKeyOrNull, false, userId)
                : get(BASE + "/runs?user={user}&game={game}&status=new&embed=game,category&max=20", apiKeyOrNull,
                        false, userId, gameIdOrNull);
        List<SpeedrunRun> runs = new ArrayList<>();
        for (JsonNode run : array(answer)) {
            run(run, run.path("game"), run.path("category"), null).ifPresent(runs::add);
        }
        return runs;
    }

    // --- HTTP ---

    /**
     * GET, parsed; empty on 404 (and on 403 when {@code forbiddenIsEmpty}) or an unreadable body. A 420 or 429
     * pauses the limiter, any other failure (5xx, 403 elsewhere) is {@link SpeedrunUnavailableException}.
     */
    private Optional<JsonNode> get(String template, String apiKey, boolean forbiddenIsEmpty, Object... variables) {
        if (limiter.paused()) {
            throw new SpeedrunUnavailableException("speedrun.com is paused after a rate limit answer");
        }
        Answer answer = send(restClient.get().uri(template, variables), apiKey);
        for (int hop = 0; isRedirect(answer.status()) && answer.location() != null && hop < MAX_REDIRECTS; hop++) {
            URI target = URI.create(SITE).resolve(answer.location());
            if (!target.toString().startsWith(SITE + API_PATH)) {
                throw new SpeedrunUnavailableException("speedrun.com redirected out of its API");
            }
            answer = send(restClient.get().uri(target), apiKey);
        }
        int status = answer.status().value();
        if (status == 420 || status == 429) {
            limiter.pause(PAUSE);
            throw new SpeedrunUnavailableException("speedrun.com rate limit hit (" + status + ")");
        }
        if (status == 404 || status == 403 && forbiddenIsEmpty) {
            return Optional.empty();
        }
        if (!answer.status().is2xxSuccessful()) {
            throw new SpeedrunUnavailableException("speedrun.com answered " + status);
        }
        return parse(answer.body());
    }

    private Answer send(RestClient.RequestHeadersSpec<?> request, String apiKey) {
        RestClient.RequestHeadersSpec<?> spec = request.header(HttpHeaders.ACCEPT, "application/json");
        if (apiKey != null) {
            spec = spec.header("X-API-Key", apiKey);
        }
        return spec.exchange((req, res) -> new Answer(res.getStatusCode(), res.getHeaders().getFirst(HttpHeaders.LOCATION),
                res.getStatusCode().is2xxSuccessful() ? res.bodyTo(String.class) : null));
    }

    private static boolean isRedirect(HttpStatusCode status) {
        return status.is3xxRedirection();
    }

    private Optional<JsonNode> parse(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readTree(body));
        } catch (JacksonException e) {
            log.warn("speedrun.com answer is not JSON: {}", e.getOriginalMessage());
            return Optional.empty();
        }
    }

    // --- mapping ---

    private JsonNode array(Optional<JsonNode> answer) {
        return answer.map(json -> json.path("data")).filter(JsonNode::isArray).orElseGet(mapper::createArrayNode);
    }

    private static Optional<SpeedrunGame> game(JsonNode node) {
        if (!node.hasNonNull("id")) {
            return Optional.empty();
        }
        JsonNode names = node.path("names");
        return Optional.of(new SpeedrunGame(node.get("id").asString(), text(names.path("international")),
                text(names.path("twitch")), text(node.path("abbreviation")), text(node.path("weblink")),
                text(node.path("assets").path("cover-large").path("uri"))));
    }

    private static Optional<SpeedrunUser> user(JsonNode node) {
        if (!node.hasNonNull("id")) {
            return Optional.empty();
        }
        return Optional.of(new SpeedrunUser(node.get("id").asString(), text(node.path("names").path("international")),
                text(node.path("weblink")), text(node.path("twitch").path("uri"))));
    }

    private static Optional<SpeedrunRecords.CategoryRecord> category(JsonNode entry, String gameId,
                                                                      String defaultTiming) {
        JsonNode category = entry.path("category").path("data");
        Set<SpeedrunRecords.Runner> runners = new LinkedHashSet<>();
        JsonNode first = null;
        for (JsonNode ranked : entry.path("runs")) {
            if (ranked.path("place").asInt(0) != 1) {
                continue;
            }
            JsonNode run = ranked.path("run");
            if (first == null) {
                first = run;
            }
            for (JsonNode player : run.path("players")) {
                runners.add(runner(player, entry.path("players").path("data")));
            }
        }
        if (first == null || !first.path("times").path("primary_t").isNumber()) {
            return Optional.empty();
        }
        JsonNode times = first.path("times");
        return Optional.of(new SpeedrunRecords.CategoryRecord(text(category.path("id")), text(category.path("name")),
                text(category.path("weblink")), new BigDecimal(times.get("primary_t").asString()),
                timing(times, defaultTiming), List.copyOf(runners), date(first.path("date")),
                text(first.path("weblink")), text(first.path("videos").path("links").path(0).path("uri"))));
    }

    /** The runner of a run's player reference, named from the embedded players when there are any. */
    private static SpeedrunRecords.Runner runner(JsonNode player, JsonNode embedded) {
        if ("guest".equals(player.path("rel").asString(""))) {
            return new SpeedrunRecords.Runner(text(player.path("name")), null);
        }
        String id = text(player.path("id"));
        for (JsonNode candidate : embedded) {
            if (id != null && id.equals(text(candidate.path("id")))) {
                return new SpeedrunRecords.Runner(text(candidate.path("names").path("international")),
                        text(candidate.path("weblink")));
            }
        }
        return new SpeedrunRecords.Runner(id, null);
    }

    /** The time key that is not null and equals the reference time, else the game's default one. */
    private static String timing(JsonNode times, String defaultTiming) {
        JsonNode primary = times.path("primary_t");
        for (String key : TIMINGS) {
            if (times.hasNonNull(key) && times.path(key + "_t").isNumber()
                    && times.get(key + "_t").decimalValue().compareTo(primary.decimalValue()) == 0) {
                return key;
            }
        }
        return defaultTiming;
    }

    private static String defaultTiming(JsonNode game) {
        String timing = text(game.path("ruleset").path("default-time"));
        return timing != null ? timing : "realtime";
    }

    private static Optional<SpeedrunRun> run(JsonNode run, JsonNode game, JsonNode category, Integer place) {
        if (!run.hasNonNull("id") || run.hasNonNull("level") || !run.path("times").path("primary_t").isNumber()) {
            return Optional.empty();
        }
        JsonNode gameData = game.has("data") ? game.get("data") : game;
        JsonNode categoryData = category.has("data") ? category.get("data") : category;
        String gameId = gameData.isObject() ? text(gameData.path("id")) : text(run.path("game"));
        String categoryId = categoryData.isObject() ? text(categoryData.path("id")) : text(run.path("category"));
        return Optional.of(new SpeedrunRun(gameId, text(gameData.path("names").path("international")), categoryId,
                text(categoryData.path("name")), place, new BigDecimal(run.get("times").get("primary_t").asString()),
                date(run.path("date")), instant(run.path("submitted")), text(run.path("weblink"))));
    }

    private static String text(JsonNode node) {
        return node.isString() && !node.asString().isBlank() ? node.asString() : null;
    }

    private static LocalDate date(JsonNode node) {
        try {
            return node.isString() ? LocalDate.parse(node.asString()) : null;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Instant instant(JsonNode node) {
        try {
            return node.isString() ? Instant.parse(node.asString()) : null;
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
