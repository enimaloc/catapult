package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.service.xbox.XboxStoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static fr.enimaloc.catapult.api.userapi.RequestLocale.ACCEPT_LANGUAGE_DESCRIPTION;
import static fr.enimaloc.catapult.api.userapi.RequestLocale.LANG_DESCRIPTION;

/**
 * Public lookups of a game by each platform's own id (IGDB id, Steam appId, Microsoft Store product id, DTDD id): the
 * targets of the "more" links of {@code /api/me/game}. The id in the URL is self-sufficient, so no caller is
 * resolved and no credential is needed; open to any origin, the answer carrying nothing of anyone.
 */
@RestController
@RequestMapping(path = ApiV2.GAMES_PATH, version = ApiV2.VERSION)
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "Games", description = "Public per-platform game detail, keyed by the platform's own id; "
        + "no token needed.")
public class GamesController {

    private final GameInfoAssembler gameInfoAssembler;

    @Operation(summary = "Get full IGDB detail for a game", description = "Public lookup by IGDB "
            + "game id — served from IgdbGameDetailsService's stale-while-revalidate cache, so a "
            + "cache miss triggers a live IGDB fetch (rating, genres, media, dlcs/similarGames "
            + "with their own \"more\" links back into this same endpoint, etc.).")
    @ApiResponse(responseCode = "200", description = "The IGDB game's detail.")
    @ApiResponse(responseCode = "404", description = "No IGDB game with that id.", content = @Content)
    @GetMapping("/igdb/{igdbId}")
    public IgdbDetailResponse igdbDetail(
            @Parameter(description = "IGDB game id.", in = ParameterIn.PATH, example = "1234")
            @PathVariable String igdbId) {
        return found(gameInfoAssembler.igdb(igdbId, GameInfoAssembler.LinkStyle.CURRENT), "Unknown IGDB game");
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
    public SteamDetailResponse steamDetail(
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
            @Parameter(description = ACCEPT_LANGUAGE_DESCRIPTION, in = ParameterIn.HEADER,
                    example = "fr-FR,fr;q=0.9")
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        return found(gameInfoAssembler.steam(appId, RequestLocale.parse(lang, acceptLanguage),
                        GameInfoAssembler.LinkStyle.CURRENT),
                "Unknown Steam app");
    }

    @Operation(summary = "Get full Microsoft Store detail for a product", description = "Public "
            + "lookup by Microsoft Store product id, via the unofficial, unauthenticated Display "
            + "Catalog API (no XSTS/linked-account session needed — see XboxStoreServiceImpl).")
    @ApiResponse(responseCode = "200", description = "The Xbox/Microsoft Store product's detail.")
    @ApiResponse(responseCode = "404", description = "No product with that id, or the catalog "
            + "fetch failed.", content = @Content)
    @GetMapping("/xbox/{productId}")
    public XboxStoreService.XboxProduct xboxDetail(
            @Parameter(description = "Microsoft Store product id.", in = ParameterIn.PATH,
                    example = "9NBLGGH2JHXJ")
            @PathVariable String productId,
            @Parameter(description = LANG_DESCRIPTION, example = "fr", schema = @Schema(defaultValue = "en"))
            @RequestParam(name = "lang", required = false) String lang,
            @Parameter(description = ACCEPT_LANGUAGE_DESCRIPTION, in = ParameterIn.HEADER,
                    example = "fr-FR,fr;q=0.9")
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        return found(gameInfoAssembler.xbox(productId, RequestLocale.parse(lang, acceptLanguage)),
                "Unknown Microsoft Store product");
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
    public DtddDetailResponse dtddDetail(
            @Parameter(description = "DTDD (doesthedogdie.com) numeric item id.", in = ParameterIn.PATH,
                    example = "16758")
            @PathVariable long dtddId) {
        return found(gameInfoAssembler.dtdd(dtddId), "Unknown DTDD title");
    }

    /** The detail, or a 404 problem saying {@code detail}. */
    private static <T> T found(Optional<T> answer, String detail) {
        return answer.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, detail));
    }
}
