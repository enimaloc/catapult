package fr.enimaloc.catapult.service.enrichment.riot;

import fr.enimaloc.catapult.getter.riot.RiotApiKeyRotator;
import fr.enimaloc.catapult.service.ApiKeyHasher;
import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.Champion;
import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.Champions;
import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.LeagueEntry;
import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.LiveGame;
import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.LolSummoner;
import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.RiotAccountRef;
import fr.enimaloc.catapult.service.enrichment.riot.RiotPlatform.Cluster;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@link RiotClient} over {@code https://<platform|cluster>.api.riotgames.com}, with a key of the shared pool, and
 * Data Dragon ({@code ddragon.leagueoflegends.com}, no key). The key only travels in the {@code X-Riot-Token} header
 * (never in a URL), and no key or body is ever put in an exception message or a log. Redirects are never followed and
 * any 3xx is an error; a 2xx that is not JSON or not of the expected shape is an error, never "absent"; only Riot's
 * JSON 404 ({@code {"status":{"status_code":404}}}) means "not found". A key Riot refuses (401, 403) is set aside and
 * the call is tried once more with another key. Every request asks the {@link RiotRateLimiter} first.
 */
@Component
public class HttpRiotClient implements RiotClient {

    static final String DDRAGON = "https://ddragon.leagueoflegends.com";
    static final String TOKEN_HEADER = "X-Riot-Token";
    static final String USER_AGENT = "Catapult/1.0 (+https://catapult.enimaloc.fr)";
    static final int MAX_BYTES = 1024 * 1024;
    /** Data Dragon's champion list is about 160 kB in 2026. */
    static final int MAX_DDRAGON_BYTES = 4 * 1024 * 1024;
    private static final Duration PAUSE = Duration.ofSeconds(60);
    /** The longest pause a 429 can ask for: a larger Retry-After is not trusted. */
    private static final Duration MAX_PAUSE = Duration.ofMinutes(10);
    /** How long a refused key is set aside. */
    static final Duration REJECTED_FOR = Duration.ofHours(1);
    /** How long a key Riot answered 403 is set aside (an expired key, or a route it may not use). */
    static final Duration FORBIDDEN_FOR = Duration.ofMinutes(5);
    private static final Pattern VERSION = Pattern.compile("\\d{1,4}(\\.\\d{1,4}){1,3}");
    private static final Pattern LOCALE = Pattern.compile("[a-z]{2}_[A-Z]{2}");

    private record Answer(int status, String retryAfter, String limitType, String appLimit, byte[] body,
                          boolean tooBig) {
    }

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RestClient restClient;
    private final RiotApiKeyRotator keys;
    private final RiotRateLimiter limiter;

    @Autowired
    public HttpRiotClient(RestClient.Builder builder, RiotApiKeyRotator keys, RiotRateLimiter limiter) {
        this(builder.requestFactory(requestFactory()).build(), keys, limiter);
    }

    HttpRiotClient(RestClient restClient, RiotApiKeyRotator keys, RiotRateLimiter limiter) {
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

    // --- Riot API ---

    @Override
    public RiotAccountRef accountByRiotId(Cluster cluster, String gameName, String tagLine) {
        Cluster host = cluster.accountCluster();
        JsonNode json = object(riot(host, host.host() + "/riot/account/v1/accounts/by-riot-id/{name}/{tag}",
                gameName, tagLine));
        if (!json.path("puuid").isString() || text(json.path("puuid")) == null) {
            throw garbled();
        }
        String name = text(json.path("gameName"));
        String tag = text(json.path("tagLine"));
        return new RiotAccountRef(json.get("puuid").asString(), name == null ? gameName : name,
                tag == null ? tagLine : tag);
    }

    @Override
    public LolSummoner lolSummoner(RiotPlatform platform, String puuid) {
        JsonNode json = object(riot(platform.cluster(), platform.host() + "/lol/summoner/v4/summoners/by-puuid/{puuid}",
                puuid));
        if (!json.path("summonerLevel").isNumber()) {
            throw garbled();
        }
        return new LolSummoner(json.get("summonerLevel").asInt(), integer(json.path("profileIconId")));
    }

    @Override
    public List<LeagueEntry> lolLeague(RiotPlatform platform, String puuid) {
        return entries(riot(platform.cluster(), platform.host() + "/lol/league/v4/entries/by-puuid/{puuid}", puuid));
    }

    @Override
    public Optional<LiveGame> lolActiveGame(RiotPlatform platform, String puuid) {
        JsonNode json;
        try {
            json = object(riot(platform.cluster(),
                    platform.host() + "/lol/spectator/v5/active-games/by-summoner/{puuid}", puuid));
        } catch (RiotNotFoundException e) {
            return Optional.empty();
        }
        if (!json.path("participants").isArray()) {
            throw garbled();
        }
        Integer champion = null;
        for (JsonNode participant : json.get("participants")) {
            if (puuid.equals(text(participant.path("puuid")))) {
                champion = integer(participant.path("championId"));
            }
        }
        Instant started = json.path("gameStartTime").isNumber() && json.get("gameStartTime").asLong() > 0
                ? Instant.ofEpochMilli(json.get("gameStartTime").asLong()) : null;
        return Optional.of(new LiveGame(text(json.path("gameMode")), text(json.path("gameType")),
                integer(json.path("gameQueueConfigId")), champion, started,
                json.path("gameLength").isNumber() ? json.get("gameLength").asLong() : null));
    }

    @Override
    public List<LeagueEntry> tftLeague(RiotPlatform platform, String puuid) {
        return entries(riot(platform.cluster(), platform.host() + "/tft/league/v1/by-puuid/{puuid}", puuid));
    }

    private List<LeagueEntry> entries(JsonNode json) {
        if (!json.isArray()) {
            throw garbled();
        }
        List<LeagueEntry> entries = new ArrayList<>();
        for (JsonNode entry : json) {
            if (!entry.isObject() || text(entry.path("queueType")) == null) {
                throw garbled();
            }
            entries.add(new LeagueEntry(entry.get("queueType").asString(), text(entry.path("tier")),
                    text(entry.path("rank")), entry.path("leaguePoints").asInt(0), entry.path("wins").asInt(0),
                    entry.path("losses").asInt(0)));
        }
        return entries;
    }

    // --- Data Dragon ---

    @Override
    public String latestVersion() {
        JsonNode json = ddragon(DDRAGON + "/api/versions.json", MAX_BYTES);
        if (!json.isArray() || json.isEmpty()) {
            throw garbled();
        }
        String version = text(json.get(0));
        if (version == null || !VERSION.matcher(version).matches()) {
            throw garbled();
        }
        return version;
    }

    @Override
    public Champions champions(String version, String locale) {
        if (!VERSION.matcher(version).matches() || !LOCALE.matcher(locale).matches()) {
            throw new IllegalArgumentException("not a Data Dragon version or locale");
        }
        JsonNode json = object(ddragon(DDRAGON + "/cdn/" + version + "/data/" + locale + "/champion.json",
                MAX_DDRAGON_BYTES));
        JsonNode data = json.path("data");
        if (!data.isObject() || data.isEmpty()) {
            throw garbled();
        }
        Map<String, Champion> byKey = new HashMap<>();
        for (JsonNode champion : data.values()) {
            String key = text(champion.path("key"));
            String id = text(champion.path("id"));
            if (key == null || id == null) {
                throw garbled();
            }
            String name = text(champion.path("name"));
            byKey.put(key, new Champion(id, name == null ? id : name));
        }
        return new Champions(version, byKey);
    }

    /** The URL of a champion's square image on Data Dragon. */
    public static String championIconUrl(String version, String championId) {
        return DDRAGON + "/cdn/" + version + "/img/champion/" + championId + ".png";
    }

    // --- HTTP ---

    /** GET with a key that serves {@code cluster}; a refused key is set aside and another one tried once. */
    private JsonNode riot(Cluster cluster, String template, Object... vars) {
        String tried = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            String key = keys.nextKey(cluster)
                    .orElseThrow(() -> new RiotUnavailableException("no Riot API key serves this cluster"));
            if (key.equals(tried)) {
                break;
            }
            tried = key;
            String bucket = ApiKeyHasher.id(key) + "@" + host(template, vars);
            Answer answer = send(bucket, template, key, MAX_BYTES, vars);
            limiter.learn(bucket, answer.appLimit());
            if (answer.status() == 401 || answer.status() == 403) {
                // 403 is also a route the key is not allowed: set aside for a short while only
                keys.onKeyRejected(key, answer.status() == 401 ? REJECTED_FOR : FORBIDDEN_FOR);
                continue;
            }
            if (answer.status() == 429 && "application".equalsIgnoreCase(answer.limitType())) {
                keys.onKeyRateLimited(key, retryAfter(answer.retryAfter()));
            }
            return interpret(bucket, answer);
        }
        throw new RiotUnavailableException("Riot refused the API key");
    }

    private JsonNode ddragon(String url, int maxBytes) {
        return interpret("ddragon", send("ddragon", url, null, maxBytes));
    }

    /** The host of a template: its scheme and authority (the bucket's region), before any variable. */
    private static String host(String template, Object... vars) {
        int slash = template.indexOf('/', "https://".length());
        return slash < 0 ? template : template.substring(0, slash);
    }

    /** Charges the limiter, then sends; the key is only ever put in the {@code X-Riot-Token} header. */
    private Answer send(String bucket, String template, String key, int maxBytes, Object... vars) {
        if (!limiter.acquire(bucket)) {
            throw new RiotUnavailableException("the Riot rate limit does not allow a request now");
        }
        if (key != null) {
            keys.onCall(key);
        }
        try {
            RestClient.RequestHeadersSpec<?> request = restClient.get().uri(template, vars)
                    .header(HttpHeaders.ACCEPT, "application/json").header(HttpHeaders.USER_AGENT, USER_AGENT);
            if (key != null) {
                request = request.header(TOKEN_HEADER, key);
            }
            return request.exchange((req, res) -> {
                int status = res.getStatusCode().value();
                HttpHeaders headers = res.getHeaders();
                String retryAfter = headers.getFirst(HttpHeaders.RETRY_AFTER);
                String limitType = headers.getFirst("X-Rate-Limit-Type");
                String appLimit = headers.getFirst("X-App-Rate-Limit");
                if (status != 404 && !res.getStatusCode().is2xxSuccessful()) {
                    return new Answer(status, retryAfter, limitType, appLimit, null, false);
                }
                byte[] body = readLimited(res.getBody(), maxBytes);
                return new Answer(status, retryAfter, limitType, appLimit, body, body == null);
            });
        } catch (RestClientException e) {
            throw new RiotUnavailableException("the Riot request failed");
        }
    }

    /** At most {@code max} bytes; null when there are more. */
    static byte[] readLimited(InputStream in, int max) throws IOException {
        byte[] bytes = in.readNBytes(max + 1);
        return bytes.length > max ? null : bytes;
    }

    private JsonNode interpret(String bucket, Answer answer) {
        int status = answer.status();
        if (status == 429) {
            limiter.pause(bucket, retryAfter(answer.retryAfter()));
            throw new RiotUnavailableException("Riot rate limit hit (429)");
        }
        if (answer.tooBig()) {
            throw new RiotUnavailableException("the Riot answer is too big");
        }
        if (status == 404) {
            JsonNode body = parseOrNull(answer.body());
            if (body != null && body.path("status").path("status_code").asInt(0) == 404) {
                throw new RiotNotFoundException("Riot does not know it");
            }
            throw new RiotUnavailableException("Riot answered 404 without a recognised body");
        }
        if (status < 200 || status >= 300) {
            throw new RiotUnavailableException("Riot answered " + status);
        }
        JsonNode json = parseOrNull(answer.body());
        if (json == null) {
            throw new RiotUnavailableException("the Riot answer is not JSON");
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

    private static RiotUnavailableException garbled() {
        return new RiotUnavailableException("the Riot answer does not have the expected shape");
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
        return node.isNumber() ? node.asInt() : null;
    }
}
