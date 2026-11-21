package fr.enimaloc.catapult.service.enrichment.hltb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Scrapes HowLongToBeat's own site search. The only class that knows the site's format, so a change on their side is
 * fixed here and nowhere else.
 * <p>
 * The site (a Next.js app) asks {@code GET /api/search/site/init?t=<epoch ms>} for a short-lived token, then posts
 * the search to {@code /api/search/site} with it in {@code x-auth-token}; a {@code 403} means the token expired, and
 * it is renewed once. Durations come back in seconds.
 * <p>
 * Polite on purpose: one search at a time, at most one request per {@code hltb.scrape-interval}, an explicit
 * User-Agent, and a breaker that stops asking for {@code hltb.breaker-pause} after {@code hltb.breaker-threshold}
 * failed searches in a row. All of it is per instance and in memory.
 */
@Slf4j
@Component
public class HttpHltbScraper implements HltbScraper {

    static final String SITE = "https://howlongtobeat.com";
    private static final String SEARCH_PATH = "/api/search/site";
    /** Lowest name similarity (0..1) accepted when no result matches the name exactly. */
    private static final double MIN_SIMILARITY = 0.85;

    /** Waits; swapped in tests so they never really sleep. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final RestClient http;
    private final EnrichmentProperties properties;
    private final Clock clock;
    private final Sleeper sleeper;
    private final ReentrantLock lock = new ReentrantLock(true);

    // guarded by lock
    private String token;
    private Instant lastRequestAt;
    private int consecutiveFailures;
    private Instant pausedUntil;

    @Autowired
    public HttpHltbScraper(RestClient.Builder builder, EnrichmentProperties properties,
                           @Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this(builder, properties, baseUrl, Clock.systemUTC(), duration -> Thread.sleep(duration));
    }

    HttpHltbScraper(RestClient.Builder builder, EnrichmentProperties properties, String baseUrl, Clock clock,
                    Sleeper sleeper) {
        String origin = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = builder.baseUrl(SITE)
                .defaultHeader(HttpHeaders.USER_AGENT, "Catapult (+" + origin + ")")
                .defaultHeader(HttpHeaders.REFERER, SITE + "/")
                .defaultHeader(HttpHeaders.ORIGIN, SITE)
                .build();
        this.properties = properties;
        this.clock      = clock;
        this.sleeper    = sleeper;
    }

    @Override
    public Optional<TimeToBeat> search(String name) {
        lock.lock();
        try {
            EnrichmentProperties.Hltb settings = properties.hltb();
            Instant now = clock.instant();
            if (pausedUntil != null && now.isBefore(pausedUntil)) {
                throw new UnavailableException("HowLongToBeat scraping paused until " + pausedUntil);
            }
            try {
                Optional<TimeToBeat> result = bestMatch(name, fetch(name, settings.scrapeInterval()));
                consecutiveFailures = 0;
                pausedUntil = null;
                return result;
            } catch (RuntimeException e) {
                recordFailure(settings);
                throw e instanceof UnavailableException unavailable ? unavailable
                        : new UnavailableException("HowLongToBeat search failed: " + e.getMessage(), e);
            }
        } finally {
            lock.unlock();
        }
    }

    private void recordFailure(EnrichmentProperties.Hltb settings) {
        consecutiveFailures++;
        if (consecutiveFailures >= settings.breakerThreshold()) {
            pausedUntil = clock.instant().plus(settings.breakerPause());
            log.warn("[HLTB] {} failed searches in a row, scraping paused until {}", consecutiveFailures,
                    pausedUntil);
        }
    }

    /** The site's results for {@code name}, renewing an expired token once. */
    private List<Entry> fetch(String name, Duration interval) {
        if (token == null) {
            token = newToken(interval);
        }
        try {
            return post(name, interval);
        } catch (HttpClientErrorException.Forbidden e) {
            log.debug("[HLTB] search token refused, renewing it");
            token = newToken(interval);
            return post(name, interval);
        }
    }

    private String newToken(Duration interval) {
        awaitTurn(interval);
        InitResponse init = http.get().uri(SEARCH_PATH + "/init?t=" + clock.millis())
                .retrieve().body(InitResponse.class);
        if (init == null || init.token() == null || init.token().isBlank()) {
            throw new UnavailableException("HowLongToBeat gave no search token");
        }
        return init.token();
    }

    private List<Entry> post(String name, Duration interval) {
        awaitTurn(interval);
        SearchResponse response = http.post().uri(SEARCH_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-auth-token", token)
                .body(searchBody(name))
                .retrieve().body(SearchResponse.class);
        if (response == null || response.data() == null) {
            throw new UnavailableException("HowLongToBeat search answered without results");
        }
        log.debug("[HLTB] search '{}' — {} result(s)", name, response.data().size());
        return response.data();
    }

    /** Spaces consecutive requests by at least {@code interval}. */
    private void awaitTurn(Duration interval) {
        if (lastRequestAt != null) {
            Duration wait = Duration.between(clock.instant(), lastRequestAt.plus(interval));
            if (wait.isPositive()) {
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new UnavailableException("Interrupted while waiting for HowLongToBeat", e);
                }
            }
        }
        lastRequestAt = clock.instant();
    }

    /** The body the site's own search box posts, games only, first page. */
    private static Map<String, Object> searchBody(String name) {
        Map<String, Object> include = Map.of("mode", "include", "values", List.of());
        Map<String, Object> anyTime = new HashMap<>();
        anyTime.put("min", null);
        anyTime.put("max", null);
        return Map.of(
                "searchType", "games",
                "searchTerms", Arrays.asList(name.trim().split("\\s+")),
                "searchPage", 1,
                "size", 20,
                "searchOptions", Map.of(
                        "games", Map.of("userId", 0, "platform", "", "sortCategory", "popular",
                                "rangeCategory", "main", "rangeTime", anyTime,
                                "gameplay", Map.of("perspective", include, "flow", include, "genre", include),
                                "year", "", "modifier", ""),
                        "users", Map.of("sortCategory", "postcount"),
                        "lists", Map.of("sortCategory", "follows"),
                        "filter", "", "sort", 0, "randomizer", 0),
                "useCache", true);
    }

    /** The result named exactly {@code name} (or aliased so), else the closest name above the threshold. */
    private static Optional<TimeToBeat> bestMatch(String name, List<Entry> entries) {
        String wanted = matchKey(name);
        Optional<Entry> exact = entries.stream()
                .filter(entry -> entry.names().map(HttpHltbScraper::matchKey).anyMatch(wanted::equals))
                .findFirst();
        Optional<Entry> best = exact.or(() -> entries.stream()
                .filter(entry -> entry.gameName() != null)
                .map(entry -> Map.entry(entry, similarity(wanted, matchKey(entry.gameName()))))
                .filter(scored -> scored.getValue() >= MIN_SIMILARITY)
                .max(Comparator.comparingDouble(Map.Entry::getValue))
                .map(Map.Entry::getKey));
        return best.map(HttpHltbScraper::toTimeToBeat);
    }

    private static TimeToBeat toTimeToBeat(Entry entry) {
        return new TimeToBeat(positive(entry.compMain()), positive(entry.compPlus()), positive(entry.comp100()),
                (int) entry.compAllCount(), TimeToBeat.Origin.HLTB, SITE + "/game/" + entry.gameId());
    }

    private static Long positive(long seconds) {
        return seconds > 0 ? seconds : null;
    }

    /** Lower case, accents and punctuation dropped, single spaces: "Hollow Knight™" and "hollow knight" match. */
    private static String matchKey(String name) {
        String plain = Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        return plain.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** 1 minus the edit distance over the longer length. */
    private static double similarity(String a, String b) {
        int longer = Math.max(a.length(), b.length());
        return longer == 0 ? 1 : 1 - (double) levenshtein(a, b) / longer;
    }

    private static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InitResponse(String token) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResponse(List<Entry> data) {
    }

    /** One search result; durations in seconds, 0 when unknown. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Entry(@JsonProperty("game_id") long gameId,
                 @JsonProperty("game_name") String gameName,
                 @JsonProperty("game_alias") String gameAlias,
                 @JsonProperty("comp_main") long compMain,
                 @JsonProperty("comp_plus") long compPlus,
                 @JsonProperty("comp_100") long comp100,
                 @JsonProperty("comp_all_count") long compAllCount) {

        /** Its name and every comma-separated alias. */
        Stream<String> names() {
            Stream<String> aliases = gameAlias == null ? Stream.empty() : Arrays.stream(gameAlias.split(","));
            return Stream.concat(Stream.of(gameName), aliases).filter(Objects::nonNull);
        }
    }
}
