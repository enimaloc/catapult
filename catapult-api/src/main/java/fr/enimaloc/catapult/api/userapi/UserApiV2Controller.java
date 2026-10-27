package fr.enimaloc.catapult.api.userapi;

import com.fasterxml.jackson.annotation.JsonView;
import fr.enimaloc.catapult.service.xbox.XboxStoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

@RequiredArgsConstructor
@RestController
@RequestMapping(path = ApiV2.PATH, version = ApiV2.VERSION)
@Tag(name = "User API v2", description = "Per-platform widget game-info: an aggregate endpoint "
        + "keyed by widget token, plus five public \"more\" detail routes keyed directly by each "
        + "platform's own id (igdb id, Steam appId, Xbox Store product id, DTDD id) or, for "
        + "Catapult's own tw/ccl config, the GameBinding id. Versioned via the X-API-Version "
        + "request header (see ApiVersioningConfig) rather than a URL prefix — the path is stable "
        + "and version-neutral; omitting the header defaults to the latest version.")
public class UserApiV2Controller {

    private static final String FIELD_DESCRIPTION = "One field of the answer, as plain text instead of "
            + "JSON: a dotted path (name, igdb.url, releaseDate.releaseDate). For chat bots that can't "
            + "read JSON; a list is joined with \", \", a missing field is empty.";
    private static final String LANG_DESCRIPTION = "BCP 47 language tag used to localize/re-fetch "
            + "platform data (Steam store text and date format, trigger warning labels, Xbox "
            + "store text). Falls back to the Accept-Language request header, then English. For "
            + "the list of locales Steam actually supports, see the \"lang\" parameter on GET "
            + "/steam/{appId}.";

    private static final String ACCEPT_LANGUAGE_DESCRIPTION = "Fallback for \"lang\" when that "
            + "query param is omitted — used by clients that can set headers (this route is also "
            + "meant to be pasted as a plain URL into an OBS browser source, which sends no "
            + "custom headers, hence \"lang\" taking priority). The highest-quality (\"q=\") tag "
            + "is used; unparseable values are ignored.";

    private final GameInfoAssembler gameInfoAssembler;
    private final JsonMapper jsonMapper;

    // "lang" is a query param rather than relying on Accept-Language: this route is meant to be
    // pasted as a plain URL into an OBS browser source / overlay, which sends no such header.
    //
    // Open to any origin: the widget token in the URL is the only access control (same trust
    // model as pasting the URL into OBS/Twitchat/any other overlay tool), and the response carries
    // no cookies/credentials, so there's nothing origin-restriction would protect here. Twitchat's
    // "HTTP call" trigger action in particular fetches this in the background from its own page
    // context (confirmed via HAR: Origin: https://twitchat.fr) — same quirk as
    // WidgetTwitchatController.actionPage, generalized to any caller instead of one hardcoded origin.
    @Operation(summary = "Get the widget's currently-detected game, fully aggregated",
            description = "Resolves the widget token (or a dev backdoor UUID, see "
                    + "DevBackdoorResolver) to the currently-detected game, and returns every "
                    + "platform object that could be resolved for it (igdb/steam/xbox/dtdd/"
                    + "catapult, each null if unavailable), each carrying a \"more\" link to its "
                    + "own dedicated detail route.")
    @ApiResponse(responseCode = "200", description = "inGame=false with every platform object "
            + "null if the widget has no detected game yet; otherwise the aggregated game info.")
    @ApiResponse(responseCode = "404", description = "Unknown widget token.", content = @Content)
    @CrossOrigin(origins = "*")
    @GetMapping("/game/{uuid}")
    public ResponseEntity<?> gameInfo(
            @Parameter(description = "Per-user widget token, or a dev/test backdoor UUID (see "
                    + "DevBackdoorResolver) — the sole access control for this public endpoint.",
                    in = ParameterIn.PATH, example = ApiV2.GENERIC_UUID_EXAMPLE,
                    schema = @Schema(defaultValue = ApiV2.GENERIC_UUID_EXAMPLE))
            @PathVariable UUID uuid,
            @Parameter(description = LANG_DESCRIPTION, example = "fr", schema = @Schema(defaultValue = "en"))
            @RequestParam(name = "lang", required = false) String lang,
            @Parameter(description = ACCEPT_LANGUAGE_DESCRIPTION, in = ParameterIn.HEADER, example = "fr-FR,fr;q=0.9")
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            @Parameter(description = FIELD_DESCRIPTION, example = "igdb.url")
            @RequestParam(name = "f", required = false) String field) {
        return selected(gameInfoFor(uuid, RequestLocale.parse(lang, acceptLanguage)), null, field);
    }

    @Operation(summary = "Get a compact summary of the widget's currently-detected game",
            description = "Same aggregation as GET /game/{uuid}, but serialized through Jackson's "
                    + "GameInfoResponse.Summary @JsonView: only inGame plus the cross-platform "
                    + "derived fields (name, description, tws, releaseDate — the first non-null "
                    + "value found across igdb/steam/xbox/dtdd/catapult, in that priority order) "
                    + "are included, not the full per-platform objects.")
    @ApiResponse(responseCode = "200", description = "inGame=false if the widget has no detected "
            + "game yet; otherwise the summarized game info.")
    @ApiResponse(responseCode = "404", description = "Unknown widget token.", content = @Content)
    @CrossOrigin(origins = "*")
    @GetMapping("/summary/{uuid}")
    @JsonView(GameInfoResponse.Summary.class)
    public ResponseEntity<?> summary(
            @Parameter(description = "Per-user widget token, or a dev/test backdoor UUID (see "
                    + "DevBackdoorResolver) — the sole access control for this public endpoint.",
                    in = ParameterIn.PATH, example = ApiV2.GENERIC_UUID_EXAMPLE,
                    schema = @Schema(defaultValue = ApiV2.GENERIC_UUID_EXAMPLE))
            @PathVariable UUID uuid,
            @Parameter(description = LANG_DESCRIPTION, example = "fr", schema = @Schema(defaultValue = "en"))
            @RequestParam(name = "lang", required = false) String lang,
            @Parameter(description = ACCEPT_LANGUAGE_DESCRIPTION, in = ParameterIn.HEADER, example = "fr-FR,fr;q=0.9")
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            @Parameter(description = FIELD_DESCRIPTION, example = "name")
            @RequestParam(name = "f", required = false) String field) {
        return selected(gameInfoFor(uuid, RequestLocale.parse(lang, acceptLanguage)), GameInfoResponse.Summary.class, field);
    }

    /**
     * {@code info} as is, or its field {@code field} alone as plain text (see {@link FieldSelector}):
     * for the chat bots that can't read JSON. A 404 stays one.
     */
    private ResponseEntity<?> selected(ResponseEntity<GameInfoResponse> info, Class<?> view, String field) {
        if (field == null || field.isBlank() || info.getBody() == null) return info;
        return ResponseEntity.status(info.getStatusCode())
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .body(FieldSelector.select(jsonMapper, info.getBody(), view, field.strip()));
    }

    /**
     * The aggregated game info behind a widget token or dev backdoor uuid: 404 for an unknown
     * token, inGame=false while the widget's channel plays nothing detected.
     */
    private ResponseEntity<GameInfoResponse> gameInfoFor(UUID uuid, Locale locale) {
        return gameInfoAssembler.gameInfo(uuid, locale)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // These "more" routes are keyed directly by the platform's own public id (igdb id, Steam
    // appId, Xbox Store product id, DTDD id) rather than the widget token: unlike /game/{uuid},
    // they don't need to know which user or which "currently playing" game they're about — the
    // id in the URL is self-sufficient — so they're plain public lookups, no widget/backdoor
    // resolution involved.
    @Operation(summary = "Get full IGDB detail for a game", description = "Public lookup by IGDB "
            + "game id — served from IgdbGameDetailsService's stale-while-revalidate cache, so a "
            + "cache miss triggers a live IGDB fetch (rating, genres, media, dlcs/similarGames "
            + "with their own \"more\" links back into this same endpoint, etc.).")
    @ApiResponse(responseCode = "200", description = "The IGDB game's detail.")
    @ApiResponse(responseCode = "404", description = "No IGDB game with that id.", content = @Content)
    @GetMapping("/igdb/{igdbId}")
    public ResponseEntity<IgdbDetailResponse> igdbDetail(
            @Parameter(description = "IGDB game id.", in = ParameterIn.PATH, example = "1234")
            @PathVariable String igdbId) {
        return gameInfoAssembler.igdb(igdbId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Get full Steam store detail for an app", description = "Public lookup "
            + "by Steam appId — fetches the store appdetails page live (no local cache), plus "
            + "content-label (ccl) data and, if this app is a demo/beta/playtest, the resolved "
            + "parent app. releaseDate is a parsed Instant, not Steam's raw locale-formatted "
            + "string (see SteamReleaseDate).")
    @ApiResponse(responseCode = "200", description = "The Steam app's store-page detail.")
    @ApiResponse(responseCode = "404", description = "No Steam app with that id, or the store "
            + "fetch failed.", content = @Content)
    @GetMapping("/steam/{appId}")
    public ResponseEntity<SteamDetailResponse> steamDetail(
            @Parameter(description = "Steam appId.", in = ParameterIn.PATH, example = "400")
            @PathVariable String appId,
            @Parameter(description = "BCP 47 language tag Steam's appdetails API is queried with "
                    + "(see SteamLanguages) — controls the store description/date-format language, "
                    + "not just a display hint. Unlisted tags silently fall back to English.",
                    example = "fr",
                    schema = @Schema(defaultValue = "en", allowableValues = {
                            "en", "fr", "de", "it", "es", "pt", "pt-BR", "zh", "zh-TW", "zh-HK",
                            "ru", "ja", "ko", "th", "tr", "uk", "nl", "da", "fi", "no", "sv", "pl",
                            "hu", "cs", "ro", "bg", "el", "vi", "ar", "id"}))
            @RequestParam(name = "lang", required = false) String lang,
            @Parameter(description = ACCEPT_LANGUAGE_DESCRIPTION, in = ParameterIn.HEADER, example = "fr-FR,fr;q=0.9")
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        return gameInfoAssembler.steam(appId, RequestLocale.parse(lang, acceptLanguage))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Get full Microsoft Store detail for a product", description = "Public "
            + "lookup by Microsoft Store product id, via the unofficial, unauthenticated Display "
            + "Catalog API (no XSTS/linked-account session needed — see XboxStoreServiceImpl).")
    @ApiResponse(responseCode = "200", description = "The Xbox/Microsoft Store product's detail.")
    @ApiResponse(responseCode = "404", description = "No product with that id, or the catalog "
            + "fetch failed.", content = @Content)
    @GetMapping("/xbox/{productId}")
    public ResponseEntity<XboxStoreService.XboxProduct> xboxDetail(
            @Parameter(description = "Microsoft Store product id.", in = ParameterIn.PATH, example = "9NBLGGH2JHXJ")
            @PathVariable String productId,
            @Parameter(description = LANG_DESCRIPTION, example = "fr", schema = @Schema(defaultValue = "en"))
            @RequestParam(name = "lang", required = false) String lang,
            @Parameter(description = ACCEPT_LANGUAGE_DESCRIPTION, in = ParameterIn.HEADER, example = "fr-FR,fr;q=0.9")
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        return gameInfoAssembler.xbox(productId, RequestLocale.parse(lang, acceptLanguage))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Get full \"Does the Dog Die\" content-warning detail for a title",
            description = "Public lookup by DTDD numeric id — yes/no/mostly trigger-warning "
                    + "topic votes plus item metadata (overview, genres, poster/background "
                    + "images, per-topic vote counts). 404 if the dtdd.enabled feature flag is "
                    + "off, not just if the id doesn't resolve.")
    @ApiResponse(responseCode = "200", description = "The title's DTDD content-warning detail.")
    @ApiResponse(responseCode = "404", description = "DTDD support disabled, or no title with "
            + "that id.", content = @Content)
    @GetMapping("/dtdd/{dtddId}")
    public ResponseEntity<DtddDetailResponse> dtddDetail(
            @Parameter(description = "DTDD (doesthedogdie.com) numeric item id.", in = ParameterIn.PATH, example = "16758")
            @PathVariable long dtddId) {
        return gameInfoAssembler.dtdd(dtddId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // Catapult data has no platform-side id — it's this app's own per-binding tw/ccl config —
    // so it's keyed by the GameBinding's own id instead of a platform id, still without any
    // widget-token check (same public-lookup-by-id model as the other "more" routes above).
    @Operation(summary = "Get full Catapult binding detail (tw/ccl config)", description = "Public "
            + "lookup by GameBinding id — there's no platform-side id for this data, it's "
            + "Catapult's own per-binding trigger-warning/content-label configuration. Unlike the "
            + "other \"more\" routes, this isn't keyed by a public platform id but by this app's "
            + "own internal binding id.")
    @ApiResponse(responseCode = "200", description = "The binding's tw/ccl configuration.")
    @ApiResponse(responseCode = "404", description = "No binding with that id.", content = @Content)
    @GetMapping("/catapult/{bindingId}")
    public ResponseEntity<CatapultDetailResponse> catapultDetail(
            @Parameter(description = "GameBinding id (as returned by the \"more\" link on the "
                    + "catapult object of /game or /summary).", in = ParameterIn.PATH)
            @PathVariable UUID bindingId,
            @Parameter(description = "BCP 47 language tag used to localize the returned "
                    + "twLabels. Falls back to the Accept-Language request header, then English.",
                    example = "fr", schema = @Schema(defaultValue = "en"))
            @RequestParam(name = "lang", required = false) String lang,
            @Parameter(description = ACCEPT_LANGUAGE_DESCRIPTION, in = ParameterIn.HEADER, example = "fr-FR,fr;q=0.9")
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        return gameInfoAssembler.catapult(bindingId, RequestLocale.parse(lang, acceptLanguage))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
