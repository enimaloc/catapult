package fr.enimaloc.catapult.assets;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The third-party files the pages use (Google Fonts' Material Icons, Twitchat's icons on GitHub),
 * fetched by catapult-web itself and served from its own origin ({@link ExternalAssetController}),
 * so a visitor's browser never contacts Google or GitHub.
 *
 * <p>Only fixed upstreams are ever asked: the Material Icons style sheet, font files under Google's
 * font host, and SVGs of Twitchat's icon folder at a given commit; the caller checks each path
 * element against a strict pattern before it gets here. Redirects aren't followed, an answer over
 * its kind's size limit is refused, and the style sheet is rewritten to point at the proxied fonts
 * (one pointing anywhere else is refused). Answers are kept in memory (bounded): an instance fetches
 * each file once a day at most, and only once while it is being fetched. A file that can't be fetched
 * again once expired is served as it was kept, a while longer: an upstream that fails or hangs then
 * holds no page waiting on it (the style sheet blocks a page's rendering). The style sheet and its
 * fonts are fetched once the instance is ready, so that its first visitor doesn't wait on them either.
 */
@Slf4j
@Component
public class ExternalAssets {
    /** Google Fonts answers each browser the formats it reads: woff2 for this one, as for any recent browser. */
    static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";
    static final String FONTS_PATH = "/assets/ext/fonts/gstatic/";
    static final long MAX_CACHED_BYTES = 32L * 1024 * 1024;
    static final int MAX_ENTRIES = 2000;
    static final Duration MISSING_TTL = Duration.ofMinutes(10);
    private static final Pattern CSS_URL = Pattern.compile("url\\(\\s*(['\"]?)([^'\")]*)\\1\\s*\\)");
    /** A font file's path under Google's font host, as the style sheet names them. */
    static final Pattern FONT_PATH = Pattern.compile("s/[a-z0-9]+/v[0-9]+/[A-Za-z0-9_-]+\\.(woff2|woff|ttf)");

    /** What is served: its status (200, 404 upstream; 502 when it can't be had) and for 200 its body. */
    public record Result(int status, byte[] body) {
        static final Result MISSING = new Result(404, null);
        static final Result FAILED = new Result(502, null);
    }

    private record Entry(Result result, Instant until) {}

    private final HttpClient http;
    private final Clock clock;
    private final URI materialIconsCss;
    private final String fontsBase;
    private final String twitchatBase;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final AtomicLong cachedBytes = new AtomicLong();
    private final Map<String, CompletableFuture<Result>> inflight = new ConcurrentHashMap<>();

    @Autowired
    public ExternalAssets(
            @Value("${catapult.external-assets.material-icons-css:https://fonts.googleapis.com/icon?family=Material+Icons}") String materialIconsCss,
            @Value("${catapult.external-assets.fonts-base:https://fonts.gstatic.com/}") String fontsBase,
            @Value("${catapult.external-assets.twitchat-base:https://raw.githubusercontent.com/Durss/Twitchat/}") String twitchatBase) {
        this(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build(), Clock.systemUTC(), materialIconsCss, fontsBase, twitchatBase);
    }

    ExternalAssets(HttpClient http, Clock clock, String materialIconsCss, String fontsBase, String twitchatBase) {
        this.http = http;
        this.clock = clock;
        this.materialIconsCss = URI.create(materialIconsCss);
        this.fontsBase = fontsBase;
        this.twitchatBase = twitchatBase;
    }

    /** The Material Icons style sheet, its fonts pointing at {@link #FONTS_PATH}. */
    public Result materialIconsCss() {
        return get("css:material-icons", materialIconsCss, Kind.CSS, this::rewriteCss);
    }

    /** Fetches the style sheet and the fonts it names, off the startup thread. */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        Thread.ofVirtual().name("external-assets-warm-up").start(this::fetchIconFonts);
    }

    /** The style sheet, then each font it points at (through the proxy). */
    void fetchIconFonts() {
        Result css = materialIconsCss();
        if (css.status() != 200) return;
        Matcher matcher = Pattern.compile(Pattern.quote(FONTS_PATH) + "(" + FONT_PATH.pattern() + ")")
                .matcher(new String(css.body(), StandardCharsets.UTF_8));
        while (matcher.find()) font(matcher.group(1));
    }

    /** A font file of Google's font host; {@code path} must match {@link #FONT_PATH}. */
    public Result font(String path) {
        if (!FONT_PATH.matcher(path).matches()) return Result.MISSING;
        return get("font:" + path, URI.create(fontsBase + path), Kind.FONT, UnaryOperator.identity());
    }

    /** One of Twitchat's icons at {@code commit}; both are checked by the caller's route. */
    public Result twitchatIcon(String commit, String name) {
        URI uri = URI.create(twitchatBase + commit + "/src_front/assets/icons/" + name + ".svg");
        return get("twitchat:" + commit + "/" + name, uri, Kind.SVG, svg -> looksLikeSvg(svg) ? svg : null);
    }

    /** Each kind's size limit and how long it is kept (a font or a commit's icon never changes). */
    enum Kind {
        CSS(64 * 1024, Duration.ofDays(1)),
        FONT(2 * 1024 * 1024, Duration.ofDays(7)),
        SVG(256 * 1024, Duration.ofDays(7));

        final int maxBytes;
        final Duration ttl;

        Kind(int maxBytes, Duration ttl) {
            this.maxBytes = maxBytes;
            this.ttl = ttl;
        }
    }

    private Result get(String key, URI uri, Kind kind, UnaryOperator<byte[]> transform) {
        Instant now = clock.instant();
        Entry entry = cache.get(key);
        if (entry != null && now.isBefore(entry.until())) return entry.result();

        CompletableFuture<Result> mine = new CompletableFuture<>();
        CompletableFuture<Result> running = inflight.putIfAbsent(key, mine);
        if (running != null) return running.join();
        try {
            Result result = fetch(uri, kind, transform);
            if (result == Result.FAILED && entry != null && entry.result().status() == 200) {
                log.info("External asset {} served as kept, for {}", uri, MISSING_TTL);
                result = entry.result();
                keep(key, new Entry(result, now.plus(MISSING_TTL)), now);
            } else if (result.status() == 200) keep(key, new Entry(result, now.plus(kind.ttl)), now);
            else if (result.status() == 404) keep(key, new Entry(result, now.plus(MISSING_TTL)), now);
            mine.complete(result);
            return result;
        } catch (RuntimeException e) {
            mine.complete(Result.FAILED);
            throw e;
        } finally {
            inflight.remove(key, mine);
        }
    }

    private Result fetch(URI uri, Kind kind, UnaryOperator<byte[]> transform) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", USER_AGENT)
                .GET().build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                int status = response.statusCode();
                if (status == 404) return Result.MISSING;
                if (status != 200) {
                    log.warn("External asset {} answered {}", uri, status);
                    return Result.FAILED;
                }
                if (response.headers().firstValueAsLong("Content-Length").orElse(-1) > kind.maxBytes) {
                    log.warn("External asset {} is over {} bytes", uri, kind.maxBytes);
                    return Result.FAILED;
                }
                byte[] body = in.readNBytes(kind.maxBytes + 1);
                if (body.length > kind.maxBytes) {
                    log.warn("External asset {} is over {} bytes", uri, kind.maxBytes);
                    return Result.FAILED;
                }
                byte[] served = transform.apply(body);
                if (served == null) {
                    log.warn("External asset {} isn't what it should be", uri);
                    return Result.FAILED;
                }
                return new Result(200, served);
            }
        } catch (IOException e) {
            log.warn("External asset {} failed: {}", uri, e.getMessage());
            return Result.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.FAILED;
        }
    }

    /** The style sheet with each font's URL pointing at the proxy; null when one points anywhere else. */
    byte[] rewriteCss(byte[] css) {
        Matcher matcher = CSS_URL.matcher(new String(css, StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String url = matcher.group(2).trim();
            if (!url.startsWith(fontsBase) || !FONT_PATH.matcher(url.substring(fontsBase.length())).matches()) return null;
            matcher.appendReplacement(out, Matcher.quoteReplacement("url(" + FONTS_PATH + url.substring(fontsBase.length()) + ")"));
        }
        matcher.appendTail(out);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static boolean looksLikeSvg(byte[] body) {
        String start = new String(body, 0, Math.min(body.length, 512), StandardCharsets.UTF_8).stripLeading();
        return start.startsWith("<svg") || start.startsWith("<?xml");
    }

    /** Kept unless the cache is full (in entries or bytes) even once its expired entries are dropped. */
    private void keep(String key, Entry entry, Instant now) {
        long size = entry.result().body() == null ? 0 : entry.result().body().length;
        if (cache.size() >= MAX_ENTRIES || cachedBytes.get() + size > MAX_CACHED_BYTES) {
            cache.entrySet().removeIf(e -> {
                if (now.isBefore(e.getValue().until())) return false;
                byte[] body = e.getValue().result().body();
                cachedBytes.addAndGet(body == null ? 0 : -body.length);
                return true;
            });
            if (cache.size() >= MAX_ENTRIES || cachedBytes.get() + size > MAX_CACHED_BYTES) return;
        }
        Entry previous = cache.put(key, entry);
        byte[] replaced = previous == null ? null : previous.result().body();
        cachedBytes.addAndGet(size - (replaced == null ? 0 : replaced.length));
    }
}
