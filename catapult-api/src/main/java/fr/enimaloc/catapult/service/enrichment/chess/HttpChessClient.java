package fr.enimaloc.catapult.service.enrichment.chess;

import fr.enimaloc.catapult.service.enrichment.EnrichmentUserAgent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * {@link ChessClient} over {@code https://api.chess.com/pub/player/} and {@code https://lichess.org/api/user/}: no
 * key, the shared Catapult User-Agent. Redirects are never followed and any 3xx is an error (Chess.com redirects a
 * name with capitals to the lowercase one: the name is lowercased first); a 2xx that is not JSON or not of the
 * expected shape is an error, never "absent"; only the sites' recognised JSON 404 bodies mean "not found". Every
 * request goes through the {@link ChessRateLimiter} (one in flight per site); a 429 pauses the site.
 */
@Component
public class HttpChessClient implements ChessClient {

    static final String CHESSCOM = "https://api.chess.com/pub/player/";
    static final String LICHESS = "https://lichess.org/api/user/";
    private static final Duration PAUSE = Duration.ofSeconds(60);
    /** The longest pause a 429 can ask for: a larger Retry-After is not trusted. */
    private static final Duration MAX_PAUSE = Duration.ofMinutes(10);
    /** A player with many daily games in progress gets every PGN: well under this. */
    static final int MAX_BYTES = 4 * 1024 * 1024;

    /** The Chess.com time classes kept, by their stats key. */
    private static final Map<String, String> CHESSCOM_MODES = Map.of(
            "chess_daily", "daily",
            "chess960_daily", "daily960",
            "chess_rapid", "rapid",
            "chess_blitz", "blitz",
            "chess_bullet", "bullet");

    private record Answer(int status, String retryAfter, byte[] body, boolean tooBig) {
    }

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RestClient restClient;
    private final ChessRateLimiter limiter;

    @Autowired
    public HttpChessClient(RestClient.Builder builder, ChessRateLimiter limiter) {
        this(builder.requestFactory(requestFactory()).build(), limiter);
    }

    HttpChessClient(RestClient restClient, ChessRateLimiter limiter) {
        this.restClient = restClient;
        this.limiter = limiter;
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

    // --- Chess.com ---

    @Override
    public ChesscomPlayer chesscomPlayer(String username) {
        JsonNode json = object(get(ChessSite.CHESSCOM, chesscom(username), chesscomNotFound()));
        if (!json.path("player_id").isNumber() || text(json.path("username")) == null) {
            throw garbled();
        }
        return new ChesscomPlayer(json.get("player_id").asLong(), json.get("username").asString(),
                text(json.path("url")), text(json.path("title")), text(json.path("avatar")));
    }

    @Override
    public ChesscomStats chesscomStats(String username) {
        JsonNode json = object(get(ChessSite.CHESSCOM, chesscom(username, "stats"), chesscomNotFound()));
        // every player has at least the FIDE rating or the tactics; nothing known at all is a broken answer
        if (!json.has("fide") && !json.has("tactics") && !json.has("puzzle_rush")
                && CHESSCOM_MODES.keySet().stream().noneMatch(json::has)) {
            throw garbled();
        }
        Map<String, ChessRating> modes = new LinkedHashMap<>();
        for (Map.Entry<String, String> mode : CHESSCOM_MODES.entrySet()) {
            JsonNode node = json.path(mode.getKey());
            JsonNode last = node.path("last");
            if (!last.path("rating").isNumber()) {
                continue;
            }
            JsonNode record = node.path("record");
            Integer win = integer(record.path("win"));
            Integer loss = integer(record.path("loss"));
            Integer draw = integer(record.path("draw"));
            Integer games = win == null || loss == null || draw == null ? null : win + loss + draw;
            modes.put(mode.getValue(), new ChessRating(last.get("rating").asInt(), integer(last.path("rd")), null,
                    null, integer(node.path("best").path("rating")), games, win, loss, draw,
                    seconds(last.path("date"))));
        }
        Integer fide = integer(json.path("fide"));
        return new ChesscomStats(modes, fide == null || fide <= 0 ? null : fide);
    }

    @Override
    public List<ChesscomDailyGame> chesscomGames(String username) {
        String me = username.toLowerCase(Locale.ROOT);
        JsonNode json = object(get(ChessSite.CHESSCOM, chesscom(username, "games"), chesscomNotFound()));
        if (!json.path("games").isArray()) {
            throw garbled();
        }
        List<ChesscomDailyGame> games = new ArrayList<>();
        for (JsonNode game : json.get("games")) {
            String url = text(game.path("url"));
            String white = lastSegment(text(game.path("white")));
            String black = lastSegment(text(game.path("black")));
            if (url == null || white == null || black == null) {
                throw garbled();
            }
            String color = me.equals(white) ? "white" : me.equals(black) ? "black" : null;
            String opponent = "white".equals(color) ? black : "black".equals(color) ? white : null;
            games.add(new ChesscomDailyGame(url, color, opponent,
                    color != null && color.equals(text(game.path("turn"))), seconds(game.path("move_by")),
                    text(game.path("rules"))));
        }
        return games;
    }

    private static URI chesscom(String username, String... rest) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(CHESSCOM)
                .pathSegment(username.toLowerCase(Locale.ROOT));
        builder.pathSegment(rest);
        return builder.encode().build().toUri();
    }

    /** {@code {"code":0,"message":"User \"…\" not found."}}. */
    private static Predicate<JsonNode> chesscomNotFound() {
        return node -> node.path("code").isNumber() && text(node.path("message")) != null
                && node.get("message").asString().endsWith("not found.");
    }

    // --- Lichess ---

    @Override
    public LichessUser lichessUser(String username) {
        URI uri = UriComponentsBuilder.fromUriString(LICHESS).pathSegment(username).encode().build().toUri();
        JsonNode json = object(get(ChessSite.LICHESS, uri,
                node -> "Not found".equals(text(node.path("error")))));
        if (text(json.path("id")) == null || text(json.path("username")) == null) {
            throw garbled();
        }
        if (json.path("disabled").asBoolean(false)) {
            throw new ChessNotFoundException("the Lichess account is closed");
        }
        Map<String, ChessRating> perfs = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> perf : json.path("perfs").properties()) {
            JsonNode node = perf.getValue();
            Integer games = integer(node.path("games"));
            // puzzles, storm and the like have no rating; a variant never played is left out
            if (!node.path("rating").isNumber() || games == null || games <= 0) {
                continue;
            }
            perfs.put(perf.getKey(), new ChessRating(node.get("rating").asInt(), integer(node.path("rd")),
                    node.path("prov").asBoolean(false), integer(node.path("prog")), null, games, null, null, null,
                    null));
        }
        JsonNode count = json.path("count");
        return new LichessUser(json.get("id").asString(), json.get("username").asString(), text(json.path("title")),
                text(json.path("url")), perfs, integer(count.path("all")), integer(count.path("win")),
                integer(count.path("loss")), integer(count.path("draw")), text(json.path("playing")),
                json.path("seenAt").isNumber() ? Instant.ofEpochMilli(json.get("seenAt").asLong()) : null);
    }

    // --- transport ---

    private JsonNode get(ChessSite site, URI uri, Predicate<JsonNode> notFound) {
        return interpret(site, limiter.call(site, () -> send(uri)), notFound);
    }

    private Answer send(URI uri) {
        try {
            return restClient.get().uri(uri).header(HttpHeaders.ACCEPT, "application/json")
                    .header(HttpHeaders.USER_AGENT, EnrichmentUserAgent.VALUE)
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
            throw new ChessUnavailableException("the chess site request failed");
        }
    }

    /** At most {@code max} bytes; null when there are more. */
    static byte[] readLimited(InputStream in, int max) throws IOException {
        byte[] bytes = in.readNBytes(max + 1);
        return bytes.length > max ? null : bytes;
    }

    private JsonNode interpret(ChessSite site, Answer answer, Predicate<JsonNode> notFound) {
        int status = answer.status();
        if (status == 429) {
            limiter.pause(site, retryAfter(answer.retryAfter()));
            throw new ChessUnavailableException(site + " rate limit hit (429)");
        }
        if (answer.tooBig()) {
            throw new ChessUnavailableException("the " + site + " answer is too big");
        }
        if (status == 404) {
            JsonNode body = parseOrNull(answer.body());
            if (body != null && notFound.test(body)) {
                throw new ChessNotFoundException(site + " does not know this account");
            }
            throw new ChessUnavailableException(site + " answered 404 without a recognised body");
        }
        if (status < 200 || status >= 300) {
            throw new ChessUnavailableException(site + " answered " + status);
        }
        JsonNode json = parseOrNull(answer.body());
        if (json == null) {
            throw new ChessUnavailableException("the " + site + " answer is not JSON");
        }
        return json;
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

    private static Duration retryAfter(String header) {
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

    private static ChessUnavailableException garbled() {
        return new ChessUnavailableException("the chess site answer does not have the expected shape");
    }

    private static JsonNode object(JsonNode node) {
        if (!node.isObject() || node.isEmpty()) {
            throw garbled();
        }
        return node;
    }

    private static String text(JsonNode node) {
        return node.isString() && !node.asString().isBlank() ? node.asString() : null;
    }

    private static Integer integer(JsonNode node) {
        return node.isNumber() ? node.asInt() : null;
    }

    private static Instant seconds(JsonNode node) {
        return node.isNumber() && node.asLong() > 0 ? Instant.ofEpochSecond(node.asLong()) : null;
    }

    /** The name at the end of a Chess.com player API link, lowercase. */
    private static String lastSegment(String url) {
        if (url == null) {
            return null;
        }
        String name = url.substring(url.lastIndexOf('/') + 1);
        return name.isBlank() ? null : name.toLowerCase(Locale.ROOT);
    }
}
