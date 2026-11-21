package fr.enimaloc.catapult.service.enrichment.pcgw;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * {@link PcGamingWikiClient} over the MediaWiki Cargo API: one query finds the page (id + name), then one query per
 * support table ({@code Video}, {@code Input}) reads the fields, since a game may lack a row in either.
 */
@Slf4j
@Component
public class HttpPcGamingWikiClient implements PcGamingWikiClient {

    private static final String API = "https://www.pcgamingwiki.com/w/api.php";
    private static final String WIKI = "https://www.pcgamingwiki.com/wiki/";

    private final RestClient restClient;
    private final JsonMapper mapper;

    public HttpPcGamingWikiClient(RestClient.Builder builder, JsonMapper mapper) {
        this.restClient = builder.build();
        this.mapper     = mapper;
    }

    @Override
    public Optional<PcGamingWikiPage> findBySteamAppId(String appId) {
        return find("Infobox_game.Steam_AppID HOLDS " + quote(appId));
    }

    @Override
    public Optional<PcGamingWikiPage> findByName(String name) {
        return find("Infobox_game._pageName=" + quote(name));
    }

    @Override
    public Optional<PcGamingWikiPage> findByPageId(long pageId) {
        return find("Infobox_game._pageID=" + pageId);
    }

    private Optional<PcGamingWikiPage> find(String where) {
        JsonNode row = firstRow("Infobox_game", "Infobox_game._pageID=pageID,Infobox_game._pageName=pageName", where);
        if (row == null || !row.path("pageID").asString("").matches("\\d+")
                || row.path("pageName").asString("").isBlank()) {
            return Optional.empty();
        }
        long pageId = Long.parseLong(row.path("pageID").asString());
        String title = row.path("pageName").asString();
        JsonNode video = firstRow("Video", "Video.Ultrawidescreen=ultrawide,Video.HDR=hdr", "Video._pageID=" + pageId);
        JsonNode input = firstRow("Input", "Input.Controller_support=controller", "Input._pageID=" + pageId);
        return Optional.of(new PcGamingWikiPage(pageId, title,
                WIKI + UriUtils.encodePathSegment(title.replace(' ', '_'), StandardCharsets.UTF_8),
                text(video, "ultrawide"), text(input, "controller"), text(video, "hdr"), List.of()));
    }

    /** The first Cargo row ({@code title} object), or null when there is none or the answer is not Cargo JSON. */
    private JsonNode firstRow(String table, String fields, String where) {
        String body = restClient.get()
                .uri(API + "?action=cargoquery&format=json&tables={t}&fields={f}&where={w}", table, fields, where)
                .retrieve()
                .body(String.class);
        try {
            JsonNode rows = mapper.readTree(body).path("cargoquery");
            if (!rows.isArray() || rows.isEmpty()) {
                return null;
            }
            JsonNode title = rows.get(0).path("title");
            return title.isObject() ? title : null;
        } catch (RuntimeException e) {
            log.warn("PCGamingWiki answered something that is not Cargo JSON for {}: {}", where, e.toString());
            return null;
        }
    }

    private static String text(JsonNode row, String field) {
        if (row == null) {
            return null;
        }
        String value = row.path(field).asString("").trim();
        return value.isEmpty() ? null : value;
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
