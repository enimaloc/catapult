package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.IpFetchBudgets;
import fr.enimaloc.catapult.service.enrichment.hltb.HltbService;
import fr.enimaloc.catapult.service.enrichment.hltb.TimeToBeat;
import fr.enimaloc.catapult.service.enrichment.pcgw.PcGamingWikiPage;
import fr.enimaloc.catapult.service.enrichment.pcgw.PcGamingWikiService;
import fr.enimaloc.catapult.service.enrichment.opencritic.OpenCriticGame;
import fr.enimaloc.catapult.service.enrichment.opencritic.OpenCriticService;
import fr.enimaloc.catapult.service.enrichment.prices.GamePrices;
import fr.enimaloc.catapult.service.enrichment.prices.PricesService;
import fr.enimaloc.catapult.service.enrichment.protondb.ProtonDbService;
import fr.enimaloc.catapult.service.enrichment.protondb.ProtonDbSummary;
import fr.enimaloc.catapult.service.enrichment.steamdeck.SteamDeckCompatibility;
import fr.enimaloc.catapult.service.enrichment.steamdeck.SteamDeckService;
import fr.enimaloc.catapult.service.enrichment.steamgriddb.SteamGridDbAssets;
import fr.enimaloc.catapult.service.enrichment.steamgriddb.SteamGridDbService;
import fr.enimaloc.catapult.service.enrichment.twitch.TwitchActivity;
import fr.enimaloc.catapult.service.enrichment.twitch.TwitchActivityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Optional;

/**
 * Public detail of the enrichment sources (HowLongToBeat, ProtonDB, Steam Deck, SteamGridDB, PCGamingWiki, prices,
 * OpenCritic, Twitch activity), keyed by
 * each source's own id: the targets of the "more" links of the v3 {@code /api/me/game}. Version 3 and later only.
 * Open to anyone, from any origin, so a cache miss may cost an upstream call: those calls are capped per client IP
 * ({@link IpFetchBudgets}), a spent budget answering {@code 429} with {@code Retry-After}
 * ({@link UpstreamBudgetAdvice}); an answer served from the cache costs nothing. A disabled source answers 404.
 */
@RestController
@RequestMapping(path = ApiV2.GAMES_PATH, version = ApiV2.VERSION_3 + "+")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "Games", description = "Public per-platform game detail, keyed by the platform's own id; "
        + "no token needed.")
public class EnrichmentGamesController {

    private static final String BUDGET_NOTE = " Served from a shared cache; a miss fetches the source live, at most "
            + "a few times a minute per client IP (429 with Retry-After beyond).";

    private final IpFetchBudgets ipFetchBudgets;
    private final HltbService hltbService;
    private final ProtonDbService protonDbService;
    private final SteamDeckService steamDeckService;
    private final SteamGridDbService steamGridDbService;
    private final PcGamingWikiService pcGamingWikiService;
    private final PricesService pricesService;
    private final OpenCriticService openCriticService;
    private final TwitchActivityService twitchActivityService;

    @Operation(summary = "Get how long an IGDB game takes to beat", description = "HowLongToBeat durations, in "
            + "seconds (rushed, normal, completionist; null when unknown), as IGDB serves them." + BUDGET_NOTE)
    @ApiResponse(responseCode = "200", description = "The game's time to beat.")
    @ApiResponse(responseCode = "404", description = "Source disabled, or no time to beat for that game.",
            content = @Content)
    @ApiResponse(responseCode = "429", description = "Too many live fetches from this IP; retry after "
            + "Retry-After seconds.", content = @Content)
    @GetMapping("/hltb/{igdbId}")
    public TimeToBeat hltbDetail(
            @Parameter(description = "IGDB game id.", in = ParameterIn.PATH, example = "1942")
            @PathVariable @Pattern(regexp = ApiV2.IGDB_ID) String igdbId,
            HttpServletRequest request) {
        return found(hltbService.timeToBeat(igdbId, budget(request)), "Unknown HowLongToBeat game");
    }

    @Operation(summary = "Get the ProtonDB summary of a Steam app", description = "ProtonDB's community "
            + "compatibility tier, trend, confidence and report count." + BUDGET_NOTE)
    @ApiResponse(responseCode = "200", description = "The app's ProtonDB summary.")
    @ApiResponse(responseCode = "404", description = "Source disabled, or no ProtonDB report for that app.",
            content = @Content)
    @ApiResponse(responseCode = "429", description = "Too many live fetches from this IP; retry after "
            + "Retry-After seconds.", content = @Content)
    @GetMapping("/protondb/{appId}")
    public ProtonDbSummary protonDbDetail(
            @Parameter(description = "Steam appId.", in = ParameterIn.PATH, example = "400")
            @PathVariable @Pattern(regexp = ApiV2.STEAM_APP_ID) String appId,
            HttpServletRequest request) {
        return found(protonDbService.summary(appId, budget(request)), "Unknown ProtonDB app");
    }

    @Operation(summary = "Get the Steam Deck compatibility of a Steam app", description = "Valve's Steam Deck "
            + "verdict and every tested item behind it." + BUDGET_NOTE)
    @ApiResponse(responseCode = "200", description = "The app's Steam Deck compatibility.")
    @ApiResponse(responseCode = "404", description = "Source disabled, or no verdict for that app.",
            content = @Content)
    @ApiResponse(responseCode = "429", description = "Too many live fetches from this IP; retry after "
            + "Retry-After seconds.", content = @Content)
    @GetMapping("/steamdeck/{appId}")
    public SteamDeckCompatibility steamDeckDetail(
            @Parameter(description = "Steam appId.", in = ParameterIn.PATH, example = "400")
            @PathVariable @Pattern(regexp = ApiV2.STEAM_APP_ID) String appId,
            HttpServletRequest request) {
        return found(steamDeckService.compatibility(appId, budget(request)), "Unknown Steam Deck app");
    }

    @Operation(summary = "Get the SteamGridDB artwork of a game", description = "Every grid, hero, logo and "
            + "icon SteamGridDB has for the game." + BUDGET_NOTE)
    @ApiResponse(responseCode = "200", description = "The game's SteamGridDB artwork.")
    @ApiResponse(responseCode = "404", description = "Source disabled, or no SteamGridDB game with that id.",
            content = @Content)
    @ApiResponse(responseCode = "429", description = "Too many live fetches from this IP; retry after "
            + "Retry-After seconds.", content = @Content)
    @GetMapping("/steamgriddb/{sgdbGameId}")
    public SteamGridDbAssets steamGridDbDetail(
            @Parameter(description = "SteamGridDB game id.", in = ParameterIn.PATH, example = "5251")
            @PathVariable @Positive long sgdbGameId,
            HttpServletRequest request) {
        return found(steamGridDbService.byGameId(sgdbGameId, budget(request)), "Unknown SteamGridDB game");
    }

    @Operation(summary = "Get the PCGamingWiki page of a game", description = "The PCGamingWiki page and its "
            + "support values (ultrawide, controller, HDR...)." + BUDGET_NOTE)
    @ApiResponse(responseCode = "200", description = "The game's PCGamingWiki page.")
    @ApiResponse(responseCode = "404", description = "Source disabled, or no PCGamingWiki page with that id.",
            content = @Content)
    @ApiResponse(responseCode = "429", description = "Too many live fetches from this IP; retry after "
            + "Retry-After seconds.", content = @Content)
    @GetMapping("/pcgamingwiki/{pageId}")
    public PcGamingWikiPage pcGamingWikiDetail(
            @Parameter(description = "PCGamingWiki page id.", in = ParameterIn.PATH, example = "3093")
            @PathVariable @Positive long pageId,
            HttpServletRequest request) {
        return found(pcGamingWikiService.byPageId(pageId, budget(request)), "Unknown PCGamingWiki page");
    }

    @Operation(summary = "Get the official-store prices of a game", description = "Current and lowest prices "
            + "per official store, from IsThereAnyDeal, in the country's currency." + BUDGET_NOTE)
    @ApiResponse(responseCode = "200", description = "The game's prices.")
    @ApiResponse(responseCode = "400", description = "Malformed id, or unknown country.", content = @Content)
    @ApiResponse(responseCode = "404", description = "Source disabled, or no prices for that game.",
            content = @Content)
    @ApiResponse(responseCode = "429", description = "Too many live fetches from this IP; retry after "
            + "Retry-After seconds.", content = @Content)
    @GetMapping("/prices/{itadId}")
    public GamePrices pricesDetail(
            @Parameter(description = "IsThereAnyDeal game id (UUID).", in = ParameterIn.PATH,
                    example = "018d937f-590c-70fa-bd4f-b95ee2c2e2f1")
            @PathVariable @Pattern(regexp = ApiV2.ITAD_ID) String itadId,
            @Parameter(description = "ISO 3166 two-letter country of the prices; FR when absent.", example = "FR")
            @RequestParam(required = false) String country,
            HttpServletRequest request) {
        String validCountry = ApiV2.country(country);
        return found(pricesService.byItadId(itadId.toLowerCase(Locale.ROOT), validCountry, budget(request)),
                "Unknown ITAD game");
    }

    @Operation(summary = "Get the OpenCritic score of a game", description = "OpenCritic's critic score, "
            + "tier and review count." + BUDGET_NOTE)
    @ApiResponse(responseCode = "200", description = "The game's OpenCritic score.")
    @ApiResponse(responseCode = "404", description = "Source disabled, or no OpenCritic game with that id.",
            content = @Content)
    @ApiResponse(responseCode = "429", description = "Too many live fetches from this IP; retry after "
            + "Retry-After seconds.", content = @Content)
    @GetMapping("/opencritic/{ocId}")
    public OpenCriticGame openCriticDetail(
            @Parameter(description = "OpenCritic game id.", in = ParameterIn.PATH, example = "463")
            @PathVariable @Pattern(regexp = ApiV2.OPENCRITIC_ID) String ocId,
            HttpServletRequest request) {
        return found(openCriticService.publicById(Long.parseLong(ocId), budget(request)), "Unknown OpenCritic game");
    }

    @Operation(summary = "Get the Twitch activity of a category", description = "Live viewers and streams, "
            + "and the top clips, of a Twitch category." + BUDGET_NOTE)
    @ApiResponse(responseCode = "200", description = "The category's Twitch activity.")
    @ApiResponse(responseCode = "400", description = "Malformed id, or bad language.", content = @Content)
    @ApiResponse(responseCode = "404", description = "Source disabled, or no Twitch category with that id.",
            content = @Content)
    @ApiResponse(responseCode = "429", description = "Too many live fetches from this IP; retry after "
            + "Retry-After seconds.", content = @Content)
    @GetMapping("/twitch/{twitchGameId}")
    public TwitchActivity twitchDetail(
            @Parameter(description = "Twitch category (game) id.", in = ParameterIn.PATH, example = "32982")
            @PathVariable @Pattern(regexp = ApiV2.TWITCH_GAME_ID) String twitchGameId,
            @Parameter(description = "Two-letter broadcaster language to restrict the lives to; all when absent.",
                    example = "fr")
            @RequestParam(required = false) String language,
            HttpServletRequest request) {
        String validLanguage = language(language);
        return found(twitchActivityService.byCategoryId(twitchGameId, validLanguage, budget(request)),
                "Unknown Twitch category");
    }

    /** The lower-cased two-letter language (so FR and fr share a cache entry); null when absent; 400 otherwise. */
    private static String language(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String language = raw.strip();
        if (!language.matches(ApiV2.LANGUAGE)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown language code");
        }
        return language.toLowerCase(Locale.ROOT);
    }

    /** The upstream budget of the caller's IP (the real client IP: forward headers are resolved natively). */
    private FetchBudget budget(HttpServletRequest request) {
        return ipFetchBudgets.forIp(request.getRemoteAddr());
    }

    /** The detail, or a 404 problem saying {@code detail}. */
    private static <T> T found(Optional<T> answer, String detail) {
        return answer.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, detail));
    }
}
