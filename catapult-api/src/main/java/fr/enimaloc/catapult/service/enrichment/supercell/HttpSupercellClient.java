package fr.enimaloc.catapult.service.enrichment.supercell;

import fr.enimaloc.catapult.getter.supercell.SupercellApiKeyRotator;
import fr.enimaloc.catapult.service.ApiKeyHasher;
import fr.enimaloc.catapult.service.enrichment.EnrichmentUserAgent;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellModels.Brawler;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellModels.BsPlayer;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellModels.Card;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellModels.CocPlayer;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellModels.CrPlayer;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellModels.Group;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellModels.Hero;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellModels.SupercellPlayer;
import fr.enimaloc.catapult.service.supercell.SupercellKeys;
import lombok.extern.slf4j.Slf4j;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * {@link SupercellClient} over each game's {@code https://api.<game>.com/v1}, with a key of that game's pool. The key
 * only travels in the {@code Authorization: Bearer} header (never in a URL), and no key, IP address or body is ever
 * put in an exception message or a log. Redirects are never followed and any 3xx is an error; a 2xx that is not JSON
 * or not of the expected shape (no tag, another tag, no trophies) is an error, never "absent"; only Supercell's JSON
 * 404 {@code {"reason":"notFound"}} means "not found". A key Supercell refuses from this server's IP
 * ({@code 403 accessDenied.invalidIp}) is set aside for its game and the call goes on with another key; any other 403
 * fails that call alone (logged with the masked key) and the key stays in use. A 429 pauses that key's bucket (its
 * {@code Retry-After}, at most 10 minutes). A key set aside is never sent. Every request asks the
 * {@link SupercellRateLimiter} first; a key it refuses now is passed over for another key of the game.
 */
@Slf4j
@Component
public class HttpSupercellClient implements SupercellClient {

    static final String USER_AGENT = EnrichmentUserAgent.VALUE;
    /** A Clash of Clans player with every troop, spell and achievement runs to a few tens of kB. */
    static final int MAX_BYTES = 1024 * 1024;
    private static final Duration PAUSE = Duration.ofSeconds(60);
    /** The longest pause a 429 can ask for: a larger Retry-After is not trusted. */
    static final Duration MAX_PAUSE = Duration.ofMinutes(10);
    static final String INVALID_IP = "accessDenied.invalidIp";
    static final String NOT_FOUND = "notFound";

    private record Answer(int status, String retryAfter, byte[] body, boolean tooBig) {
    }

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RestClient restClient;
    private final SupercellApiKeyRotator keys;
    private final SupercellRateLimiter limiter;

    @Autowired
    public HttpSupercellClient(RestClient.Builder builder, SupercellApiKeyRotator keys, SupercellRateLimiter limiter) {
        this(builder.requestFactory(requestFactory()).build(), keys, limiter);
    }

    HttpSupercellClient(RestClient restClient, SupercellApiKeyRotator keys, SupercellRateLimiter limiter) {
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

    @Override
    public SupercellPlayer player(SupercellGame game, String tag) {
        URI uri = URI.create(game.host() + "/v1/players/" + SupercellTag.pathSegment(tag));
        JsonNode json = object(get(game, uri));
        String found = text(json.path("tag"));
        String name = text(json.path("name"));
        if (found == null || !found.equalsIgnoreCase(tag) || name == null || !json.path("trophies").isNumber()) {
            throw garbled();
        }
        String spelled = found.toUpperCase(Locale.ROOT);
        return switch (game) {
            case CLASH_OF_CLANS -> new SupercellPlayer(spelled, name, clashOfClans(json), null, null);
            case CLASH_ROYALE -> new SupercellPlayer(spelled, name, null, clashRoyale(json), null);
            case BRAWL_STARS -> new SupercellPlayer(spelled, name, null, null, brawlStars(json));
        };
    }

    // --- HTTP ---

    /**
     * GET with a key of {@code game}'s pool. A key whose bucket the limiter refuses now (paused by a 429, or its
     * minute full) is passed over for the next one, and so is a key refused from this IP (set aside first); each key
     * is tried at most once per call.
     */
    private JsonNode get(SupercellGame game, URI uri) {
        Set<String> tried = new HashSet<>();
        boolean limited = false;
        boolean ipRefused = false;
        int rounds = 2 * Math.max(1, keys.keyCount(game));
        for (int attempt = 0; attempt < rounds; attempt++) {
            Optional<String> next = keys.nextKey(game);
            if (next.isEmpty()) {
                break;
            }
            String key = next.get();
            if (!tried.add(key)) {
                continue;
            }
            String bucket = "key:" + ApiKeyHasher.id(key);
            if (!limiter.acquire(bucket)) {
                limited = true;
                continue;
            }
            Answer answer = send(uri, key);
            if (answer.status() == 403) {
                String reason = reason(answer);
                if (!INVALID_IP.equals(reason)) {
                    // a refusal of this call only: the key stays in use for the others
                    log.warn("Supercell {} key {} refused (403 {})", game.key(), SupercellKeys.mask(key), reason);
                    throw new SupercellUnavailableException("Supercell refused the request (403)");
                }
                keys.onIpRefused(game, key);
                ipRefused = true;
                continue;
            }
            return interpret(bucket, answer);
        }
        throw new SupercellUnavailableException(ipRefused
                ? "Supercell refused this server's IP address for every key tried"
                : limited ? "the Supercell rate limit does not allow a request now"
                : "no usable Supercell key for this game");
    }

    /** Sends, once the limiter gave a slot; the key is only ever put in the {@code Authorization} header. */
    private Answer send(URI uri, String key) {
        keys.onCall(key);
        try {
            return restClient.get().uri(uri)
                    .header(HttpHeaders.ACCEPT, "application/json").header(HttpHeaders.USER_AGENT, USER_AGENT)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                    .exchange((req, res) -> {
                        int status = res.getStatusCode().value();
                        String retryAfter = res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
                        if (status != 403 && status != 404 && !res.getStatusCode().is2xxSuccessful()) {
                            return new Answer(status, retryAfter, null, false);
                        }
                        byte[] body = readLimited(res.getBody(), MAX_BYTES);
                        return new Answer(status, retryAfter, body, body == null);
                    });
        } catch (RestClientException e) {
            throw new SupercellUnavailableException("the Supercell request failed");
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
            throw new SupercellUnavailableException("Supercell rate limit hit (429)");
        }
        if (answer.tooBig()) {
            throw new SupercellUnavailableException("the Supercell answer is too big");
        }
        if (status == 404) {
            if (NOT_FOUND.equals(reason(answer))) {
                throw new SupercellNotFoundException("Supercell knows no player with this tag");
            }
            throw new SupercellUnavailableException("Supercell answered 404 without a recognised body");
        }
        if (status < 200 || status >= 300) {
            throw new SupercellUnavailableException("Supercell answered " + status);
        }
        JsonNode json = parseOrNull(answer.body());
        if (json == null) {
            throw new SupercellUnavailableException("the Supercell answer is not JSON");
        }
        return json;
    }

    /** The {@code reason} of a Supercell error body; null when there is none. */
    private String reason(Answer answer) {
        JsonNode body = parseOrNull(answer.body());
        return body != null && body.isObject() ? text(body.path("reason")) : null;
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

    private static CocPlayer clashOfClans(JsonNode json) {
        if (!json.path("townHallLevel").isNumber()) {
            throw garbled();
        }
        JsonNode league = json.path("league");
        List<Hero> heroes = new ArrayList<>();
        for (JsonNode hero : json.path("heroes")) {
            String name = text(hero.path("name"));
            if (name != null) {
                heroes.add(new Hero(name, integer(hero.path("level")), integer(hero.path("maxLevel")),
                        text(hero.path("village"))));
            }
        }
        return new CocPlayer(json.get("townHallLevel").asInt(), integer(json.path("townHallWeaponLevel")),
                integer(json.path("expLevel")), json.get("trophies").asInt(), integer(json.path("bestTrophies")),
                integer(json.path("warStars")), integer(json.path("attackWins")), integer(json.path("defenseWins")),
                integer(json.path("builderHallLevel")), integer(json.path("builderBaseTrophies")),
                text(league.path("name")), text(league.path("iconUrls").path("medium")), group(json.path("clan")),
                List.copyOf(heroes));
    }

    private static CrPlayer clashRoyale(JsonNode json) {
        List<Card> deck = new ArrayList<>();
        for (JsonNode card : json.path("currentDeck")) {
            String name = text(card.path("name"));
            if (name != null) {
                deck.add(new Card(name, integer(card.path("level")), integer(card.path("maxLevel"))));
            }
        }
        return new CrPlayer(integer(json.path("expLevel")), json.get("trophies").asInt(),
                integer(json.path("bestTrophies")), integer(json.path("wins")), integer(json.path("losses")),
                integer(json.path("threeCrownWins")), integer(json.path("battleCount")),
                text(json.path("arena").path("name")),
                integer(json.path("currentPathOfLegendSeasonResult").path("leagueNumber")),
                group(json.path("clan")), List.copyOf(deck));
    }

    private static BsPlayer brawlStars(JsonNode json) {
        List<Brawler> brawlers = new ArrayList<>();
        for (JsonNode brawler : json.path("brawlers")) {
            String name = text(brawler.path("name"));
            if (name != null) {
                brawlers.add(new Brawler(name, integer(brawler.path("power")), integer(brawler.path("rank")),
                        integer(brawler.path("trophies")), integer(brawler.path("highestTrophies"))));
            }
        }
        return new BsPlayer(integer(json.path("expLevel")), json.get("trophies").asInt(),
                integer(json.path("highestTrophies")), integer(json.path("3vs3Victories")),
                integer(json.path("soloVictories")), integer(json.path("duoVictories")), group(json.path("club")),
                List.copyOf(brawlers));
    }

    /** A clan or club; null when the player has none (Brawl Stars answers an empty object). */
    private static Group group(JsonNode node) {
        String name = text(node.path("name"));
        return name == null ? null : new Group(text(node.path("tag")), name);
    }

    private static SupercellUnavailableException garbled() {
        return new SupercellUnavailableException("the Supercell answer does not have the expected shape");
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
}
