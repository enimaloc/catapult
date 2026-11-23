package fr.enimaloc.catapult.service.enrichment.hypixel;

import fr.enimaloc.catapult.getter.hypixel.HypixelApiKeyRotator;
import fr.enimaloc.catapult.service.ApiKeyHasher;
import fr.enimaloc.catapult.service.enrichment.EnrichmentUserAgent;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.BedWarsRaw;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.HypixelGameInfo;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.HypixelGames;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.HypixelPlayer;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.HypixelSession;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.MinecraftProfile;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.SkyWarsRaw;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@link HypixelClient} over {@code https://api.hypixel.net/v2} with a key of the shared pool (the player routes) or
 * none (the games list), and Mojang's {@code https://api.mojang.com} profile lookup (no key). The key only travels in
 * the {@code API-Key} header (never in a URL), and no key or body is ever put in an exception message or a log.
 * Redirects are never followed and any 3xx is an error; a 2xx that is not JSON, says {@code success: false} or is
 * not of the expected shape is an error, never "absent"; only {@code {"success":true,"player":null}} (Hypixel) and
 * the JSON 404 with an {@code errorMessage} (Mojang) mean "not found". A key Hypixel refuses (403) is set aside and
 * the call is tried once more with another key; no call is sent while every key is set aside. Every request asks
 * the {@link HypixelRateLimiter} first.
 */
@Component
public class HttpHypixelClient implements HypixelClient {

    static final String HYPIXEL = "https://api.hypixel.net";
    static final String MOJANG = "https://api.mojang.com";
    static final String KEY_HEADER = "API-Key";
    static final String USER_AGENT = EnrichmentUserAgent.VALUE;
    /** A busy player's answer (every game's stats) runs to a few hundred kB. */
    static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final Duration PAUSE = Duration.ofSeconds(60);
    /** The longest pause a 429 or a spent quota can ask for: a larger one is not trusted. */
    static final Duration MAX_PAUSE = Duration.ofMinutes(10);
    /** How long a refused key is set aside. */
    static final Duration REJECTED_FOR = Duration.ofHours(1);
    static final Pattern UUID = Pattern.compile("[0-9a-f]{32}");
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    /** A Minecraft formatting code ({@code §a}). */
    private static final Pattern FORMATTING = Pattern.compile("§.");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private record Answer(int status, String retryAfter, String remaining, String reset, byte[] body,
                          boolean tooBig) {
    }

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RestClient restClient;
    private final HypixelApiKeyRotator keys;
    private final HypixelRateLimiter limiter;

    @Autowired
    public HttpHypixelClient(RestClient.Builder builder, HypixelApiKeyRotator keys, HypixelRateLimiter limiter) {
        this(builder.requestFactory(requestFactory()).build(), keys, limiter);
    }

    HttpHypixelClient(RestClient restClient, HypixelApiKeyRotator keys, HypixelRateLimiter limiter) {
        this.restClient = restClient;
        this.keys = keys;
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

    // --- Mojang ---

    @Override
    public MinecraftProfile profileByName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("not a Minecraft name");
        }
        Answer answer = send(HypixelRateLimiter.MOJANG, MOJANG + "/users/profiles/minecraft/{name}", null, name);
        if (answer.status() == 404) {
            JsonNode body = parseOrNull(answer.body());
            if (body != null && body.isObject() && text(body.path("errorMessage")) != null) {
                throw new HypixelNotFoundException("Mojang knows no such name");
            }
            throw new HypixelUnavailableException("Mojang answered 404 without a recognised body");
        }
        JsonNode json = object(interpret(HypixelRateLimiter.MOJANG, answer, false));
        String id = text(json.path("id"));
        String spelled = text(json.path("name"));
        if (id == null || !UUID.matcher(id.toLowerCase(Locale.ROOT)).matches() || spelled == null) {
            throw garbled();
        }
        return new MinecraftProfile(id.toLowerCase(Locale.ROOT), spelled);
    }

    // --- Hypixel ---

    @Override
    public HypixelPlayer player(String uuid) {
        checkUuid(uuid);
        JsonNode json = hypixel("/v2/player?uuid={uuid}", uuid);
        if (!json.has("player")) {
            throw garbled();
        }
        JsonNode player = json.get("player");
        if (player.isNull()) {
            throw new HypixelNotFoundException("Hypixel knows no such player");
        }
        if (!player.isObject() || player.isEmpty()) {
            throw garbled();
        }
        String found = text(player.path("uuid"));
        if (found == null || !found.replace("-", "").equalsIgnoreCase(uuid)) {
            throw garbled();
        }
        JsonNode stats = player.path("stats");
        JsonNode achievements = player.path("achievements");
        Instant lastLogin = instant(player.path("lastLogin"));
        return new HypixelPlayer(uuid, text(player.path("displayname")), rank(player),
                player.path("networkExp").isNumber() ? Math.max(0, player.get("networkExp").asLong()) : 0L,
                integer(player.path("karma")), integer(player.path("achievementPoints")),
                lastLogin == null, lastLogin, instant(player.path("lastLogout")),
                bedWars(stats.path("Bedwars"), achievements), skyWars(stats.path("SkyWars")));
    }

    @Override
    public HypixelSession status(String uuid) {
        checkUuid(uuid);
        JsonNode session = hypixel("/v2/status?uuid={uuid}", uuid).path("session");
        if (!session.isObject() || !session.path("online").isBoolean()) {
            throw garbled();
        }
        boolean online = session.get("online").asBoolean();
        return new HypixelSession(online, online ? text(session.path("gameType")) : null,
                online ? text(session.path("mode")) : null, online ? text(session.path("map")) : null);
    }

    @Override
    public HypixelGames games() {
        JsonNode json = object(interpret(HypixelRateLimiter.PUBLIC,
                send(HypixelRateLimiter.PUBLIC, HYPIXEL + "/v2/resources/games", null), true));
        JsonNode games = json.path("games");
        if (!games.isObject() || games.isEmpty()) {
            throw garbled();
        }
        List<HypixelGameInfo> list = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : games.properties()) {
            JsonNode game = entry.getValue();
            String name = text(game.path("name"));
            if (!game.isObject() || name == null) {
                throw garbled();
            }
            list.add(new HypixelGameInfo(entry.getKey(), name, game.path("retired").asBoolean(false),
                    game.path("legacy").asBoolean(false)));
        }
        return new HypixelGames(list);
    }

    private static void checkUuid(String uuid) {
        if (uuid == null || !UUID.matcher(uuid).matches()) {
            throw new IllegalArgumentException("not an undashed lower-case Minecraft UUID");
        }
    }

    // --- HTTP ---

    /** GET with a key of the pool; a refused key is set aside and another one tried once. */
    private JsonNode hypixel(String path, Object... vars) {
        String tried = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (keys.allBlocked()) {
                throw new HypixelUnavailableException("every Hypixel key is set aside");
            }
            String key = keys.nextKey()
                    .orElseThrow(() -> new HypixelUnavailableException("no Hypixel API key in the pool"));
            if (key.equals(tried)) {
                break;
            }
            tried = key;
            String bucket = "key:" + ApiKeyHasher.id(key);
            Answer answer = send(bucket, HYPIXEL + path, key, vars);
            if (answer.status() == 403) {
                if (!invalidKey(answer)) {
                    // a refusal of this route only: the key stays in use for the others
                    throw new HypixelUnavailableException("Hypixel refused the request (403)");
                }
                keys.onKeyRejected(key, REJECTED_FOR);
                continue;
            }
            if (answer.status() == 429) {
                // a global throttle answers 429 to every key: each one is set aside as it meets it
                keys.onKeyRateLimited(key, pause(answer.retryAfter(), answer.reset()));
            } else if ("0".equals(answer.remaining() == null ? null : answer.remaining().trim())) {
                // the key's quota is spent until the reset: the next call would be a 429
                limiter.pause(bucket, pause(null, answer.reset()));
            }
            return object(interpret(bucket, answer, true));
        }
        throw new HypixelUnavailableException("Hypixel refused the API key");
    }

    /** Hypixel's documented refusal of the key itself: a 403 {@code {"success":false,"cause":"Invalid API key"}}. */
    private boolean invalidKey(Answer answer) {
        JsonNode body = parseOrNull(answer.body());
        return body != null && "Invalid API key".equalsIgnoreCase(text(body.path("cause")));
    }

    /** Charges the limiter, then sends; the key is only ever put in the {@code API-Key} header. */
    private Answer send(String bucket, String template, String key, Object... vars) {
        if (!limiter.acquire(bucket)) {
            throw new HypixelUnavailableException("the Hypixel rate limit does not allow a request now");
        }
        if (key != null) {
            keys.onCall(key);
        }
        try {
            RestClient.RequestHeadersSpec<?> request = restClient.get().uri(template, vars)
                    .header(HttpHeaders.ACCEPT, "application/json").header(HttpHeaders.USER_AGENT, USER_AGENT);
            if (key != null) {
                request = request.header(KEY_HEADER, key);
            }
            return request.exchange((req, res) -> {
                int status = res.getStatusCode().value();
                HttpHeaders headers = res.getHeaders();
                String retryAfter = headers.getFirst(HttpHeaders.RETRY_AFTER);
                String remaining = headers.getFirst("RateLimit-Remaining");
                String reset = headers.getFirst("RateLimit-Reset");
                if (status != 403 && status != 404 && status != 429 && !res.getStatusCode().is2xxSuccessful()) {
                    return new Answer(status, retryAfter, remaining, reset, null, false);
                }
                byte[] body = readLimited(res.getBody(), MAX_BYTES);
                return new Answer(status, retryAfter, remaining, reset, body, body == null);
            });
        } catch (RestClientException e) {
            throw new HypixelUnavailableException("the Hypixel request failed");
        }
    }

    /** At most {@code max} bytes; null when there are more. */
    static byte[] readLimited(InputStream in, int max) throws IOException {
        byte[] bytes = in.readNBytes(max + 1);
        return bytes.length > max ? null : bytes;
    }

    /** The JSON of a 2xx; {@code hypixel}: it must also say {@code success: true}. */
    private JsonNode interpret(String bucket, Answer answer, boolean hypixel) {
        int status = answer.status();
        if (status == 429) {
            limiter.pause(bucket, pause(answer.retryAfter(), answer.reset()));
            throw new HypixelUnavailableException("rate limit hit (429)");
        }
        if (answer.tooBig()) {
            throw new HypixelUnavailableException("the answer is too big");
        }
        if (status < 200 || status >= 300) {
            throw new HypixelUnavailableException("answered " + status);
        }
        JsonNode json = parseOrNull(answer.body());
        if (json == null) {
            throw new HypixelUnavailableException("the answer is not JSON");
        }
        if (hypixel && !(json.path("success").isBoolean() && json.get("success").asBoolean())) {
            throw new HypixelUnavailableException("Hypixel did not answer success");
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

    /** How long to pause: {@code Retry-After}, else {@code RateLimit-Reset}, else a minute; at most 10 minutes. */
    static Duration pause(String retryAfter, String reset) {
        Duration fromRetry = seconds(retryAfter);
        if (fromRetry != null) {
            return fromRetry;
        }
        Duration fromReset = seconds(reset);
        return fromReset != null ? fromReset : PAUSE;
    }

    private static Duration seconds(String header) {
        if (header == null) {
            return null;
        }
        try {
            long seconds = Long.parseLong(header.trim());
            if (seconds <= 0) {
                return null;
            }
            return seconds > MAX_PAUSE.toSeconds() ? MAX_PAUSE : Duration.ofSeconds(seconds);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // --- mapping ---

    /** The rank shown in game: a staff rank first, then MVP++, then the bought one; null for none. */
    static String rank(JsonNode player) {
        String staff = text(player.path("rank"));
        if (staff != null && !"NORMAL".equals(staff)) {
            return staff;
        }
        if ("SUPERSTAR".equals(text(player.path("monthlyPackageRank")))) {
            return "MVP_PLUS_PLUS";
        }
        for (String field : List.of("newPackageRank", "packageRank")) {
            String rank = text(player.path(field));
            if (rank != null && !"NONE".equals(rank)) {
                return rank;
            }
        }
        return null;
    }

    private static BedWarsRaw bedWars(JsonNode bedwars, JsonNode achievements) {
        Integer stars = integer(achievements.path("bedwars_level"));
        if (!bedwars.isObject() && stars == null) {
            return null;
        }
        return new BedWarsRaw(stars, integer(bedwars.path("wins_bedwars")), integer(bedwars.path("losses_bedwars")),
                integer(bedwars.path("final_kills_bedwars")), integer(bedwars.path("final_deaths_bedwars")),
                integer(bedwars.path("beds_broken_bedwars")));
    }

    private static SkyWarsRaw skyWars(JsonNode skywars) {
        if (!skywars.isObject()) {
            return null;
        }
        return new SkyWarsRaw(skyWarsStars(text(skywars.path("levelFormatted"))), integer(skywars.path("wins")),
                integer(skywars.path("losses")), integer(skywars.path("kills")), integer(skywars.path("deaths")));
    }

    /** The star count in {@code levelFormatted} ({@code §b12⋆}): its digits once the formatting codes are gone. */
    static Integer skyWarsStars(String formatted) {
        if (formatted == null) {
            return null;
        }
        var digits = DIGITS.matcher(FORMATTING.matcher(formatted).replaceAll(""));
        if (!digits.find()) {
            return null;
        }
        try {
            return Integer.parseInt(digits.group());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static HypixelUnavailableException garbled() {
        return new HypixelUnavailableException("the answer does not have the expected shape");
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

    private static Integer integer(JsonNode node) {
        return node != null && node.isNumber() ? node.asInt() : null;
    }

    private static Instant instant(JsonNode node) {
        return node != null && node.isNumber() && node.asLong() > 0 ? Instant.ofEpochMilli(node.asLong()) : null;
    }
}
