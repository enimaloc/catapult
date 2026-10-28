package fr.enimaloc.catapult.api.userapi;

import com.fasterxml.jackson.annotation.JsonView;
import fr.enimaloc.catapult.service.xbox.XboxStoreService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * The legacy v2 widget game-info routes: an aggregate keyed by widget token, plus the public "more" detail routes
 * keyed by each platform's own id or, for Catapult's own tw/ccl config, the GameBinding id. Versioned via the
 * X-API-Version request header (see ApiVersioningConfig). Gated by {@link LegacyRoutesGate} and hidden from the
 * docs: {@code /api/me/**} and {@code /api/games/**} replace them.
 */
@Hidden
@RequiredArgsConstructor
@RestController
@RequestMapping(path = ApiV2.PATH, version = ApiV2.VERSION)
public class UserApiV2Controller {

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
    @CrossOrigin(origins = "*")
    @GetMapping("/game/{uuid}")
    public ResponseEntity<?> gameInfo(
            @PathVariable UUID uuid,
            @RequestParam(name = "lang", required = false) String lang,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            @RequestParam(name = "f", required = false) String field) {
        return FieldSelector.answer(jsonMapper, gameInfoFor(uuid, RequestLocale.parse(lang, acceptLanguage)),
                null, field);
    }

    // Same aggregation as /game/{uuid}, serialized through the GameInfoResponse.Summary view.
    @CrossOrigin(origins = "*")
    @GetMapping("/summary/{uuid}")
    @JsonView(GameInfoResponse.Summary.class)
    public ResponseEntity<?> summary(
            @PathVariable UUID uuid,
            @RequestParam(name = "lang", required = false) String lang,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            @RequestParam(name = "f", required = false) String field) {
        return FieldSelector.answer(jsonMapper, gameInfoFor(uuid, RequestLocale.parse(lang, acceptLanguage)),
                GameInfoResponse.Summary.class, field);
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
    @GetMapping("/igdb/{igdbId}")
    public ResponseEntity<IgdbDetailResponse> igdbDetail(@PathVariable String igdbId) {
        return gameInfoAssembler.igdb(igdbId, GameInfoAssembler.LinkStyle.LEGACY)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/steam/{appId}")
    public ResponseEntity<SteamDetailResponse> steamDetail(
            @PathVariable String appId,
            @RequestParam(name = "lang", required = false) String lang,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        return gameInfoAssembler.steam(appId, RequestLocale.parse(lang, acceptLanguage),
                        GameInfoAssembler.LinkStyle.LEGACY)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/xbox/{productId}")
    public ResponseEntity<XboxStoreService.XboxProduct> xboxDetail(
            @PathVariable String productId,
            @RequestParam(name = "lang", required = false) String lang,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        return gameInfoAssembler.xbox(productId, RequestLocale.parse(lang, acceptLanguage))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/dtdd/{dtddId}")
    public ResponseEntity<DtddDetailResponse> dtddDetail(@PathVariable long dtddId) {
        return gameInfoAssembler.dtdd(dtddId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // Catapult data has no platform-side id — it's this app's own per-binding tw/ccl config —
    // so it's keyed by the GameBinding's own id instead of a platform id, still without any
    // widget-token check (same public-lookup-by-id model as the other "more" routes above).
    @GetMapping("/catapult/{bindingId}")
    public ResponseEntity<CatapultDetailResponse> catapultDetail(
            @PathVariable UUID bindingId,
            @RequestParam(name = "lang", required = false) String lang,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        return gameInfoAssembler.catapult(bindingId, RequestLocale.parse(lang, acceptLanguage))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
