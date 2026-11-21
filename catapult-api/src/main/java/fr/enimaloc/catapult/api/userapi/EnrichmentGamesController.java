package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.IpFetchBudgets;
import fr.enimaloc.catapult.service.enrichment.hltb.HltbService;
import fr.enimaloc.catapult.service.enrichment.hltb.TimeToBeat;
import fr.enimaloc.catapult.service.enrichment.pcgw.PcGamingWikiPage;
import fr.enimaloc.catapult.service.enrichment.pcgw.PcGamingWikiService;
import fr.enimaloc.catapult.service.enrichment.protondb.ProtonDbService;
import fr.enimaloc.catapult.service.enrichment.protondb.ProtonDbSummary;
import fr.enimaloc.catapult.service.enrichment.steamdeck.SteamDeckCompatibility;
import fr.enimaloc.catapult.service.enrichment.steamdeck.SteamDeckService;
import fr.enimaloc.catapult.service.enrichment.steamgriddb.SteamGridDbAssets;
import fr.enimaloc.catapult.service.enrichment.steamgriddb.SteamGridDbService;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * Public detail of the enrichment sources (HowLongToBeat, ProtonDB, Steam Deck, SteamGridDB, PCGamingWiki), keyed by
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

    /** The upstream budget of the caller's IP (the real client IP: forward headers are resolved natively). */
    private FetchBudget budget(HttpServletRequest request) {
        return ipFetchBudgets.forIp(request.getRemoteAddr());
    }

    /** The detail, or a 404 problem saying {@code detail}. */
    private static <T> T found(Optional<T> answer, String detail) {
        return answer.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, detail));
    }
}
