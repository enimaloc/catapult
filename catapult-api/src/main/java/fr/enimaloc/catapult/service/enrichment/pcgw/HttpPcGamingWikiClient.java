package fr.enimaloc.catapult.service.enrichment.pcgw;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link PcGamingWikiClient} over the public MediaWiki API (Cargo is closed to anonymous callers): a full-text
 * search finds candidate pages, and the page wikitext is parsed for the Steam app id and the support fields.
 */
@Slf4j
@Component
public class HttpPcGamingWikiClient implements PcGamingWikiClient {

    private static final String API = "https://www.pcgamingwiki.com/w/api.php";
    private static final String WIKI = "https://www.pcgamingwiki.com/wiki/";
    private static final String SEARCH = API + "?action=query&format=json&list=search&srsearch={q}&srlimit=3";
    private static final String PARSE = API + "?action=parse&format=json&prop=wikitext&pageid={id}";

    /** Errors that merely mean "no such page"; anything else (permission, rate limit...) must not read as absent. */
    private static final List<String> NOT_FOUND_CODES = List.of("missingtitle", "nosuchpageid", "invalidpageid");

    private final RestClient restClient;
    private final JsonMapper mapper;

    public HttpPcGamingWikiClient(RestClient.Builder builder, JsonMapper mapper,
                                  @Value("${app.base-url}") String baseUrl) {
        this.restClient = builder.defaultHeader("User-Agent", "Catapult/1.0 (+" + baseUrl + ")").build();
        this.mapper     = mapper;
    }

    @Override
    public Optional<PcGamingWikiPage> findBySteamAppId(String appId) {
        String wanted = appId.trim();
        // The wikitext pads keys with spaces ("steam appid  = 570"); the search is only a candidate filter.
        for (Candidate candidate : search("\"steam appid  = " + wanted + "\"", true)) {
            Optional<Wikitext> page = parse(candidate.pageId());
            if (page.isPresent() && hasAppId(page.get().text(), wanted)) {
                return Optional.of(toPage(page.get()));
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<PcGamingWikiPage> findByName(String name) {
        String wanted = normalize(name);
        return search(name, false).stream()
                .filter(candidate -> normalize(candidate.title()).equals(wanted))
                .findFirst()
                .flatMap(candidate -> parse(candidate.pageId()))
                .map(HttpPcGamingWikiClient::toPage);
    }

    @Override
    public Optional<PcGamingWikiPage> findByPageId(long pageId) {
        return parse(pageId).map(HttpPcGamingWikiClient::toPage);
    }

    private List<Candidate> search(String query, boolean inText) {
        String url = inText ? SEARCH + "&srwhat=text" : SEARCH;
        JsonNode root = read(restClient.get().uri(url, query).retrieve().body(String.class));
        List<Candidate> candidates = new ArrayList<>();
        for (JsonNode hit : root.path("query").path("search")) {
            String title = hit.path("title").asString("");
            if (hit.path("pageid").canConvertToLong() && !title.isBlank()) {
                candidates.add(new Candidate(hit.path("pageid").asLong(), title));
            }
        }
        return candidates;
    }

    private Optional<Wikitext> parse(long pageId) {
        JsonNode parse = read(restClient.get().uri(PARSE, pageId).retrieve().body(String.class)).path("parse");
        String title = parse.path("title").asString("");
        String text = parse.path("wikitext").path("*").asString("");
        if (title.isBlank() || text.isBlank() || !parse.path("pageid").canConvertToLong()) {
            return Optional.empty();
        }
        return Optional.of(new Wikitext(parse.path("pageid").asLong(), title, text));
    }

    /**
     * The parsed body. Unparseable bodies (HTML...) and "no such page" errors read as an empty document; any other
     * error object throws so that the caller does not record the page as known-absent.
     */
    private JsonNode read(String body) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (RuntimeException e) {
            log.warn("PCGamingWiki answered something that is not JSON: {}", e.toString());
            return mapper.createObjectNode();
        }
        JsonNode error = root.path("error");
        if (error.isObject()) {
            String code = error.path("code").asString("");
            if (NOT_FOUND_CODES.contains(code)) {
                return mapper.createObjectNode();
            }
            throw new IllegalStateException("PCGamingWiki API error " + code + ": " + error.path("info").asString(""));
        }
        return root;
    }

    private static boolean hasAppId(String wikitext, String appId) {
        Matcher matcher = Pattern.compile("^\\|\\s*steam appid\\s*=([^\\n]*)", Pattern.MULTILINE).matcher(wikitext);
        while (matcher.find()) {
            if (Arrays.stream(matcher.group(1).split(",")).anyMatch(id -> id.trim().equals(appId))) {
                return true;
            }
        }
        return false;
    }

    private static PcGamingWikiPage toPage(Wikitext page) {
        return new PcGamingWikiPage(page.pageId(), page.title(),
                WIKI + UriUtils.encodePathSegment(page.title().replace(' ', '_'), StandardCharsets.UTF_8),
                field(page.text(), "ultrawidescreen"), field(page.text(), "controller support"),
                field(page.text(), "hdr"));
    }

    /** The first token of a {@code |key = value} line; keys such as {@code "key notes"} do not match. */
    private static String field(String wikitext, String key) {
        Matcher matcher = Pattern.compile("^\\|\\s*" + Pattern.quote(key) + "\\s*=[ \\t]*(\\S*)", Pattern.MULTILINE)
                .matcher(wikitext);
        return matcher.find() && !matcher.group(1).isEmpty() ? matcher.group(1) : null;
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private record Candidate(long pageId, String title) {
    }

    private record Wikitext(long pageId, String title, String text) {
    }
}
