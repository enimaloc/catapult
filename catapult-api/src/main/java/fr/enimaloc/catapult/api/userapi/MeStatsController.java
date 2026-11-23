package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.enrichment.chess.ChessStats;
import fr.enimaloc.catapult.service.enrichment.chess.ChessStatsService;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.MemberFetchBudgets;
import fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardStats;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardStatsService;
import fr.enimaloc.catapult.service.enrichment.faceit.FaceitStats;
import fr.enimaloc.catapult.service.enrichment.faceit.FaceitStatsService;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.FaceitGame;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.BlizzardGame;
import fr.enimaloc.catapult.service.enrichment.bungie.BungieStats;
import fr.enimaloc.catapult.service.enrichment.bungie.BungieStatsService;
import fr.enimaloc.catapult.service.enrichment.osu.OsuStats;
import fr.enimaloc.catapult.service.enrichment.osu.OsuStatsService;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelStats;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelStatsService;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellGame;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellStats;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellStatsService;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.OsuGame;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.BungieGame;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.RiotGame;
import fr.enimaloc.catapult.service.enrichment.rastats.RaAccountView;
import fr.enimaloc.catapult.service.enrichment.riot.RiotStats;
import fr.enimaloc.catapult.service.enrichment.riot.RiotStatsService;
import fr.enimaloc.catapult.service.enrichment.rastats.RetroAchievementsStatsService;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.PlatformStats;
import fr.enimaloc.catapult.service.enrichment.steamstats.SteamStatsService;
import fr.enimaloc.catapult.service.enrichment.xboxstats.XboxStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

import static fr.enimaloc.catapult.api.userapi.RequestLocale.ACCEPT_LANGUAGE_DESCRIPTION;
import static fr.enimaloc.catapult.api.userapi.RequestLocale.LANG_DESCRIPTION;

/**
 * The token owner's own play time and achievements on Steam, Xbox and RetroAchievements, and their Blizzard stats:
 * on the game their channel plays now, on a game of their choice (every achievement; every character, the ladder or
 * every hero on Blizzard), and their RetroAchievements account. Personal data: only
 * ever served here and in {@code /api/me/game}, never on the public routes. A game of their choice costs one of the
 * member's {@code catapult.enrichment.member-per-minute} refreshes, so that a token cannot walk a whole library and
 * drain the shared quotas. Version 3 and later only.
 */
@RestController
@RequestMapping(path = ApiV2.ME_STATS_PATH, version = ApiV2.VERSION_3 + "+")
@RequiredArgsConstructor
@Tag(name = "Me — stats", description = "Your own play time and achievements on Steam, Xbox and "
        + "RetroAchievements, and your Blizzard, Bungie, Riot, FACEIT, chess, osu!, Hypixel and Supercell stats, by your "
        + "personal API token (Authorization: Bearer cat_… or catr_…).")
public class MeStatsController {

    private static final String BUDGET_NOTE = " Each call takes one of your refreshes (20 a minute by default).";
    private static final String STATUS_NOTE = " A status that is not ok (private, not_owned, no_achievements, "
            + "not_found, unsupported, unavailable) is still a 200.";

    private final GameInfoAssembler gameInfoAssembler;
    private final SteamStatsService steamStatsService;
    private final XboxStatsService xboxStatsService;
    private final RetroAchievementsStatsService retroAchievementsStatsService;
    private final BlizzardStatsService blizzardStatsService;
    private final BungieStatsService bungieStatsService;
    private final RiotStatsService riotStatsService;
    private final FaceitStatsService faceitStatsService;
    private final OsuStatsService osuStatsService;
    private final HypixelStatsService hypixelStatsService;
    private final SupercellStatsService supercellStatsService;
    private final MemberFetchBudgets memberFetchBudgets;
    private final ApiUserResolver userResolver;
    private final PublicBaseUrl publicBaseUrl;
    private final ChessStatsService chessStatsService;

    /** The member's stats on the game played now: inGame=false while nothing is detected. */
    public record CurrentStats(boolean inGame, GameInfoV3Response.StatsObject stats) {
    }

    @GetMapping
    @Operation(summary = "Get your stats on the game your channel plays now",
            description = "The stats object of GET /api/me/game (version 3): steam, xbox, retroachievements, "
                    + "blizzard, bungie, riot, faceit, chess, osu, hypixel and supercell, each null when it does not "
                    + "apply (not linked, game not found there), each with its status and a \"more\" link to its detail; the "
                    + "platforms with their play time and achievements summary (unlocked, total, percent, the 5 latest, the rarest), "
                    + "blizzard with your BattleTag, region and the game's object (wow main character, sc2 or d3 "
                    + "profile), bungie with your Bungie Name and destiny2 profile, riot with your Riot ID, platform "
                    + "and the game's object (lol summoner, ranks and game in progress, tft rank), faceit with your "
                    + "nickname and your CS2 level, Elo and last match, chess with your Chess.com and Lichess ratings "
                    + "(see GET /api/me/stats/chess), osu with your osu! username and profile (pp, ranks, accuracy, "
                    + "play count and time, latest score), hypixel with your Minecraft name, network level, Bed Wars / "
                    + "SkyWars and online status, supercell with your player name and tag and the game's object "
                    + "(clashOfClans, clashRoyale or brawlStars). stats is null when nothing applies.")
    @ApiResponse(responseCode = "200", description = "inGame=false while no game is detected; otherwise your stats.")
    public CurrentStats current(
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = LANG_DESCRIPTION, example = "fr", schema = @Schema(defaultValue = "en"))
            @RequestParam(name = "lang", required = false) String lang,
            @Parameter(description = ACCEPT_LANGUAGE_DESCRIPTION, in = ParameterIn.HEADER,
                    example = "fr-FR,fr;q=0.9")
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        return gameInfoAssembler.currentStats(userResolver.tokenOwner(userId), RequestLocale.parse(lang,
                acceptLanguage));
    }

    @GetMapping("/steam/{appId}")
    @Operation(summary = "Get your play time and every achievement on a Steam app",
            description = "The steam object of GET /api/me/stats (play time in minutes, total and last two weeks, "
                    + "last played, achievements summary), plus all: every achievement of the app (unlocked or "
                    + "not, with its date and rarity; a locked hidden one without name)." + STATUS_NOTE
                    + BUDGET_NOTE)
    @ApiResponse(responseCode = "200", description = "Your stats on the app.")
    @ApiResponse(responseCode = "400", description = "Malformed appId.", content = @Content)
    @ApiResponse(responseCode = "404", description = "No Steam account linked, or Steam stats disabled.",
            content = @Content)
    @ApiResponse(responseCode = "429", description = "Your refreshes are spent; retry after Retry-After seconds.",
            content = @Content)
    public GameInfoV3Response.SteamStats steam(
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "Steam appId.", in = ParameterIn.PATH, example = "440")
            @PathVariable @Pattern(regexp = ApiV2.STEAM_APP_ID) String appId,
            @Parameter(description = LANG_DESCRIPTION, example = "fr", schema = @Schema(defaultValue = "en"))
            @RequestParam(name = "lang", required = false) String lang,
            @Parameter(description = ACCEPT_LANGUAGE_DESCRIPTION, in = ParameterIn.HEADER,
                    example = "fr-FR,fr;q=0.9")
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        PlatformStats stats = detail(userId, "No Steam stats for you", (user, budget) -> steamStatsService.forGame(
                user, appId, RequestLocale.parse(lang, acceptLanguage), budget, true));
        return GameInfoV3Response.SteamStats.of(stats, links(), true);
    }

    @GetMapping("/xbox/{titleId}")
    @Operation(summary = "Get your play time, gamerscore and every achievement on an Xbox title",
            description = "The xbox object of GET /api/me/stats (play time in minutes, last played, gamerscore, "
                    + "achievements summary), plus all: every achievement of the title (unlocked or not; a locked "
                    + "secret one without name). unsupported: Xbox refused the achievements to your session, the "
                    + "counts come from your title history." + STATUS_NOTE + BUDGET_NOTE)
    @ApiResponse(responseCode = "200", description = "Your stats on the title.")
    @ApiResponse(responseCode = "400", description = "Malformed titleId.", content = @Content)
    @ApiResponse(responseCode = "404", description = "No Xbox account linked, title not in your history, or Xbox "
            + "stats disabled.", content = @Content)
    @ApiResponse(responseCode = "429", description = "Your refreshes are spent; retry after Retry-After seconds.",
            content = @Content)
    public GameInfoV3Response.XboxStats xbox(
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "Xbox titleId (decimal).", in = ParameterIn.PATH, example = "1144039928")
            @PathVariable @Pattern(regexp = ApiV2.XBOX_TITLE_ID) String titleId,
            @Parameter(description = LANG_DESCRIPTION, example = "fr", schema = @Schema(defaultValue = "en"))
            @RequestParam(name = "lang", required = false) String lang,
            @Parameter(description = ACCEPT_LANGUAGE_DESCRIPTION, in = ParameterIn.HEADER,
                    example = "fr-FR,fr;q=0.9")
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        PlatformStats stats = detail(userId, "No Xbox stats for you on that title",
                (user, budget) -> xboxStatsService.forTitle(user, titleId, RequestLocale.parse(lang, acceptLanguage),
                        budget, true));
        return GameInfoV3Response.XboxStats.of(stats, links(), true);
    }

    @GetMapping("/retroachievements/{gameId}")
    @Operation(summary = "Get your progress and every achievement in a RetroAchievements game",
            description = "The retroachievements object of GET /api/me/stats (game, points, hardcore progress, "
                    + "highest award, play time as RetroAchievements gives it, unit not verified, achievements "
                    + "summary), plus all: every achievement (unlocked or not, rarity = share of the game's "
                    + "players who unlocked it). private: your account is untracked." + STATUS_NOTE + BUDGET_NOTE)
    @ApiResponse(responseCode = "200", description = "Your progress in the game.")
    @ApiResponse(responseCode = "400", description = "Malformed gameId.", content = @Content)
    @ApiResponse(responseCode = "404", description = "No RetroAchievements account declared or no key to read it "
            + "with, unknown game, or RetroAchievements disabled.", content = @Content)
    @ApiResponse(responseCode = "429", description = "Your refreshes are spent; retry after Retry-After seconds.",
            content = @Content)
    public GameInfoV3Response.RaStats retroAchievementsGame(
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "RetroAchievements game id.", in = ParameterIn.PATH, example = "228")
            @PathVariable @Pattern(regexp = ApiV2.RA_GAME_ID) String gameId) {
        PlatformStats stats = detail(userId, "No RetroAchievements stats for you on that game",
                (user, budget) -> retroAchievementsStatsService.forGame(user, Integer.parseInt(gameId), budget,
                        true));
        return GameInfoV3Response.RaStats.of(stats, links(), true);
    }

    @GetMapping("/blizzard/{game}")
    @Operation(summary = "Get your stats on a Blizzard game",
            description = "The blizzard object of GET /api/me/stats (status, game, account = your BattleTag, region, "
                    + "and the object of the game: wow your main character, sc2 your profile, d3 your profile), "
                    + "plus what only the detail has: wow.characters (every imported character, the main one "
                    + "marked), sc2.ladder (your ladder summary), d3.heroes (every hero). Hearthstone has no "
                    + "personal stats (its public data is in publisher.blizzard of GET /api/me/game)." + STATUS_NOTE
                    + BUDGET_NOTE)
    @ApiResponse(responseCode = "200", description = "Your stats on the game.")
    @ApiResponse(responseCode = "400", description = "Unknown game (wow, sc2 or d3).", content = @Content)
    @ApiResponse(responseCode = "404", description = "No Battle.net account linked, WoW characters not imported "
            + "(wow), or Blizzard stats disabled.", content = @Content)
    @ApiResponse(responseCode = "429", description = "Your refreshes are spent; retry after Retry-After seconds.",
            content = @Content)
    public BlizzardStats blizzard(
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "Blizzard game: wow, sc2 or d3.", in = ParameterIn.PATH, example = "wow")
            @PathVariable @Pattern(regexp = ApiV2.BLIZZARD_GAME) String game,
            @Parameter(description = LANG_DESCRIPTION, example = "fr", schema = @Schema(defaultValue = "en"))
            @RequestParam(name = "lang", required = false) String lang,
            @Parameter(description = ACCEPT_LANGUAGE_DESCRIPTION, in = ParameterIn.HEADER,
                    example = "fr-FR,fr;q=0.9")
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        BlizzardGame blizzardGame = switch (game) {
            case "wow" -> BlizzardGame.WOW;
            case "sc2" -> BlizzardGame.SC2;
            default -> BlizzardGame.D3;
        };
        String notFound = "No Blizzard stats for you on that game: Battle.net not linked (for World of Warcraft, "
                + "no character imported), or Blizzard stats unavailable";
        BlizzardStats stats = detail(userId, notFound, (user, budget) -> blizzardStatsService.forGame(user,
                blizzardGame, RequestLocale.parse(lang, acceptLanguage), budget, true));
        return GameInfoV3Response.blizzardStats(stats, links());
    }

    @GetMapping("/osu/{game}")
    @Operation(summary = "Get your stats on osu!",
            description = "The osu object of GET /api/me/stats (status, game, account = your osu! username, and osu: "
                    + "your default ruleset and its pp, global and country rank, country, accuracy, play count, play "
                    + "time and level, and your latest passed score), plus what only the detail has: osu.rulesets "
                    + "(the numbers of every ruleset) and osu.recentScores (your 5 latest passed scores). osu! asks "
                    + "that a player be read at most once a minute: the numbers are at most 2 minutes old."
                    + STATUS_NOTE + BUDGET_NOTE)
    @ApiResponse(responseCode = "200", description = "Your stats on the game.")
    @ApiResponse(responseCode = "400", description = "Unknown game (osu).", content = @Content)
    @ApiResponse(responseCode = "404", description = "No osu! account linked (or osu! refused the link since), or "
            + "osu! stats unavailable on this instance.", content = @Content)
    @ApiResponse(responseCode = "429", description = "Your refreshes are spent; retry after Retry-After seconds.",
            content = @Content)
    public OsuStats osu(
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "osu! game: osu (osu! and osu!lazer).", in = ParameterIn.PATH, example = "osu")
            @PathVariable @Pattern(regexp = ApiV2.OSU_GAME) String game) {
        String notFound = "No osu! stats for you: osu! not linked (or the link has to be made again), or osu! stats "
                + "unavailable";
        OsuStats stats = detail(userId, notFound, (user, budget) -> osuStatsService.forGame(user, OsuGame.OSU,
                budget, true));
        return GameInfoV3Response.osuStats(stats, links());
    }

    @GetMapping("/hypixel")
    @Operation(summary = "Get your Hypixel stats",
            description = "The hypixel object of GET /api/me/stats (status, game hypixel, account = your Minecraft "
                    + "name, network: level, experience, karma, achievement points, rank; bedwars and skywars: stars "
                    + "and wins; online: where you are now, private when you hide it in your Hypixel API settings), "
                    + "plus what only the detail has: bedwars losses, final kills / deaths and beds broken, skywars "
                    + "losses, kills and deaths. status not_found: the Minecraft account you declared never joined "
                    + "Hypixel." + STATUS_NOTE + BUDGET_NOTE)
    @ApiResponse(responseCode = "200", description = "Your Hypixel stats.")
    @ApiResponse(responseCode = "404", description = "No Minecraft account declared for Hypixel, or Hypixel stats "
            + "unavailable on this instance.", content = @Content)
    @ApiResponse(responseCode = "429", description = "Your refreshes are spent; retry after Retry-After seconds.",
            content = @Content)
    public HypixelStats hypixel(@AuthenticationPrincipal UUID userId) {
        HypixelStats stats = detail(userId, "No Hypixel stats for you: no Minecraft account declared, or Hypixel "
                + "stats unavailable", (user, budget) -> hypixelStatsService.forMember(user, budget, true));
        return GameInfoV3Response.hypixelStats(stats, links());
    }

    @GetMapping("/supercell/{game}")
    @Operation(summary = "Get your stats on a Supercell game",
            description = "The supercell object of GET /api/me/stats (status, game, account = your player name, tag, "
                    + "and the object of the game: clashOfClans your town hall, trophies, best trophies, league, war "
                    + "stars, builder base and clan; clashRoyale your trophies, best trophies, arena, Path of Legend "
                    + "league, wins, losses, three-crown wins and clan; brawlStars your trophies, highest trophies, 3v3 "
                    + "/ solo / duo victories and club), plus what only the detail has: Clash of Clans attack and "
                    + "defense wins and heroes, the Clash Royale battle count and current deck, the Brawl Stars "
                    + "brawlers. status not_found: no player has the tag you declared any more." + STATUS_NOTE
                    + BUDGET_NOTE)
    @ApiResponse(responseCode = "200", description = "Your stats on the game.")
    @ApiResponse(responseCode = "400", description = "Unknown game (clash-of-clans, clash-royale or brawl-stars).",
            content = @Content)
    @ApiResponse(responseCode = "404", description = "No player tag declared for that game, or Supercell stats "
            + "unavailable on this instance.", content = @Content)
    @ApiResponse(responseCode = "429", description = "Your refreshes are spent; retry after Retry-After seconds.",
            content = @Content)
    public SupercellStats supercell(
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "Supercell game: clash-of-clans, clash-royale or brawl-stars.",
                    in = ParameterIn.PATH, example = "clash-of-clans")
            @PathVariable @Pattern(regexp = ApiV2.SUPERCELL_GAME) String game) {
        SupercellGame supercellGame = SupercellGame.parse(game).orElseThrow();
        SupercellStats stats = detail(userId, "No " + supercellGame.title() + " stats for you: no player tag "
                + "declared, or Supercell stats unavailable",
                (user, budget) -> supercellStatsService.forGame(user, supercellGame, budget, true));
        return GameInfoV3Response.supercellStats(stats, links());
    }

    @GetMapping("/bungie/{game}")
    @Operation(summary = "Get your stats on a Bungie game",
            description = "The bungie object of GET /api/me/stats (status, game, account = your Bungie Name, and "
                    + "destiny2: the platform your profile is read from, guardian rank, triumph score, last played, "
                    + "the character you played last and the activity under way), plus what only the detail has: "
                    + "destiny2.characters (every character, the one played last first). The season in progress is "
                    + "public data, in publisher.bungie of GET /api/me/game." + STATUS_NOTE + BUDGET_NOTE)
    @ApiResponse(responseCode = "200", description = "Your stats on the game.")
    @ApiResponse(responseCode = "400", description = "Unknown game (destiny2).", content = @Content)
    @ApiResponse(responseCode = "404", description = "No Bungie.net account linked (or Bungie refused the link "
            + "since), or Bungie stats unavailable on this instance.", content = @Content)
    @ApiResponse(responseCode = "429", description = "Your refreshes are spent; retry after Retry-After seconds.",
            content = @Content)
    public BungieStats bungie(
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "Bungie game: destiny2.", in = ParameterIn.PATH, example = "destiny2")
            @PathVariable @Pattern(regexp = ApiV2.BUNGIE_GAME) String game,
            @Parameter(description = LANG_DESCRIPTION, example = "fr", schema = @Schema(defaultValue = "en"))
            @RequestParam(name = "lang", required = false) String lang,
            @Parameter(description = ACCEPT_LANGUAGE_DESCRIPTION, in = ParameterIn.HEADER,
                    example = "fr-FR,fr;q=0.9")
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        String notFound = "No Bungie stats for you: Bungie.net not linked (or the link has to be made again), or "
                + "Bungie stats unavailable";
        BungieStats stats = detail(userId, notFound, (user, budget) -> bungieStatsService.forGame(user,
                BungieGame.DESTINY2, RequestLocale.parse(lang, acceptLanguage), budget, true));
        return GameInfoV3Response.bungieStats(stats, links());
    }

    @GetMapping("/riot/{game}")
    @Operation(summary = "Get your stats on a Riot game",
            description = "The riot object of GET /api/me/stats (status, game, account = your Riot ID, platform, and "
                    + "the object of the game: lol your summoner level, solo / flex ranks and the game you are in "
                    + "with your champion, tft your rank), plus what only the detail has: every ranked queue "
                    + "(lol.queues, tft.queues). Valorant has no personal stats (its API needs Riot Sign On)."
                    + STATUS_NOTE + BUDGET_NOTE)
    @ApiResponse(responseCode = "200", description = "Your stats on the game.")
    @ApiResponse(responseCode = "400", description = "Unknown game (lol or tft).", content = @Content)
    @ApiResponse(responseCode = "404", description = "No Riot ID declared, or Riot stats unavailable on this "
            + "instance.", content = @Content)
    @ApiResponse(responseCode = "429", description = "Your refreshes are spent; retry after Retry-After seconds.",
            content = @Content)
    public RiotStats riot(
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "Riot game: lol or tft.", in = ParameterIn.PATH, example = "lol")
            @PathVariable @Pattern(regexp = ApiV2.RIOT_GAME) String game,
            @Parameter(description = LANG_DESCRIPTION, example = "fr", schema = @Schema(defaultValue = "en"))
            @RequestParam(name = "lang", required = false) String lang,
            @Parameter(description = ACCEPT_LANGUAGE_DESCRIPTION, in = ParameterIn.HEADER,
                    example = "fr-FR,fr;q=0.9")
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        RiotGame riotGame = "tft".equals(game) ? RiotGame.TFT : RiotGame.LOL;
        RiotStats stats = detail(userId, "No Riot stats for you: no Riot ID declared, or Riot stats unavailable",
                (user, budget) -> riotStatsService.forGame(user, riotGame, RequestLocale.parse(lang, acceptLanguage),
                        budget, true));
        return GameInfoV3Response.riotStats(stats, links());
    }

    @GetMapping("/faceit/{game}")
    @Operation(summary = "Get your stats on a FACEIT game",
            description = "The faceit object of GET /api/me/stats (status, game, account = your FACEIT nickname, "
                    + "and the object of the game: cs2 your skill level, Elo, region, lifetime matches, wins, win "
                    + "rate, average K/D and headshots, win streaks, and your last match with its map and score), "
                    + "plus what only the detail has: cs2.recentResults (your last results, newest first, true a "
                    + "win). not_found: FACEIT no longer knows your nickname, or you have no CS2 profile there."
                    + STATUS_NOTE + BUDGET_NOTE)
    @ApiResponse(responseCode = "200", description = "Your stats on the game.")
    @ApiResponse(responseCode = "400", description = "Unknown game (cs2).", content = @Content)
    @ApiResponse(responseCode = "404", description = "No FACEIT nickname declared, or FACEIT stats unavailable on "
            + "this instance.", content = @Content)
    @ApiResponse(responseCode = "429", description = "Your refreshes are spent; retry after Retry-After seconds.",
            content = @Content)
    public FaceitStats faceit(
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "FACEIT game: cs2.", in = ParameterIn.PATH, example = "cs2")
            @PathVariable @Pattern(regexp = ApiV2.FACEIT_GAME) String game) {
        FaceitStats stats = detail(userId, "No FACEIT stats for you: no FACEIT nickname declared, or FACEIT stats "
                + "unavailable", (user, budget) -> faceitStatsService.forGame(user, FaceitGame.CS2, budget, true));
        return GameInfoV3Response.faceitStats(stats, links());
    }

    @GetMapping("/chess")
    @Operation(summary = "Get your chess stats on Chess.com and Lichess",
            description = "The chess object of GET /api/me/stats (status, game = chess, account = the name shown, "
                    + "and one object per declared site: chesscom with your ratings by time class, FIDE rating, "
                    + "daily games in progress and how many wait for your move; lichess with your ratings by "
                    + "variant, game counts and the game you are playing now), plus what only the detail has: "
                    + "chesscom.games (every daily game in progress: link, colour, opponent, your move or not, move "
                    + "deadline). A site not declared is null." + STATUS_NOTE + BUDGET_NOTE)
    @ApiResponse(responseCode = "200", description = "Your chess stats.")
    @ApiResponse(responseCode = "404", description = "No Chess.com nor Lichess account declared, or chess stats "
            + "disabled.", content = @Content)
    @ApiResponse(responseCode = "429", description = "Your refreshes are spent; retry after Retry-After seconds.",
            content = @Content)
    public ChessStats chess(@AuthenticationPrincipal UUID userId) {
        ChessStats stats = detail(userId, "No chess stats for you: no Chess.com nor Lichess account declared",
                (user, budget) -> chessStatsService.forMember(user, budget, true));
        return GameInfoV3Response.chessStats(stats, links());
    }

    @GetMapping("/retroachievements")
    @Operation(summary = "Get your RetroAchievements account",
            description = "Your profile (points, rich presence, last game), the games you played lately and the "
                    + "achievements you unlocked in the last 24 hours. status private: your account is untracked "
                    + "(nothing but the name); unavailable: RetroAchievements could not be read. A list is null "
                    + "when it could not be read.")
    @ApiResponse(responseCode = "200", description = "Your account.")
    @ApiResponse(responseCode = "404", description = "No RetroAchievements account declared or no key to read it "
            + "with, or RetroAchievements disabled.", content = @Content)
    public RaAccountView retroAchievementsAccount(@AuthenticationPrincipal UUID userId) {
        return retroAchievementsStatsService.account(userResolver.tokenOwner(userId), FetchBudget.UNLIMITED)
                .orElseThrow(() -> new ApiProblem(ErrorCode.NOT_FOUND, "No RetroAchievements account for you"));
    }

    /** The member's budget is asked before anything: one call is one refresh, however many upstream calls it makes. */
    private <T> T detail(UUID userId, String notFound, BiFunction<UserAccount, FetchBudget, Optional<T>> read) {
        UserAccount user = userResolver.tokenOwner(userId);
        FetchBudget budget = FetchBudget.once(memberFetchBudgets.forMember(user.getId()));
        if (!budget.tryAcquire()) {
            throw new UpstreamBudgetExceededException(budget.retryAfter());
        }
        return read.apply(user, budget).orElseThrow(() -> new ApiProblem(ErrorCode.NOT_FOUND, notFound));
    }

    /** The "more" links of a detail answer: the caller's origin, current routes. */
    private MoreLinks links() {
        return new MoreLinks(publicBaseUrl.get(), GameInfoAssembler.LinkStyle.CURRENT);
    }

    @ExceptionHandler(UpstreamBudgetExceededException.class)
    ResponseEntity<ProblemDetail> refreshesSpent(UpstreamBudgetExceededException e) {
        return RetryAfterProblems.answer(ErrorCode.TOO_MANY_REQUESTS, "Too many stats refreshes for your account",
                e.retryAfter());
    }
}
