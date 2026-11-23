package fr.enimaloc.catapult.api.userapi;

import com.fasterxml.jackson.annotation.JsonView;
import fr.enimaloc.catapult.api.ApiUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Locale;
import java.util.UUID;

import static fr.enimaloc.catapult.api.userapi.FieldSelector.FIELD_DESCRIPTION;
import static fr.enimaloc.catapult.api.userapi.RequestLocale.ACCEPT_LANGUAGE_DESCRIPTION;
import static fr.enimaloc.catapult.api.userapi.RequestLocale.LANG_DESCRIPTION;

/**
 * The game the token owner's channel plays now, aggregated across every platform, or its summary: for overlays and
 * chat bots, authenticated by the personal API token (the read-only one opens it, from {@code ?token=} too). With
 * {@code f}, one field of the answer as plain text. Version 3 adds the enrichment sources (see
 * {@link GameInfoV3Response}); the version 2 answers are unchanged.
 */
@RestController
@RequestMapping(path = ApiV2.ME_GAME_PATH, version = ApiV2.BASELINE)
@RequiredArgsConstructor
@Tag(name = "Me — game", description = "The game your channel plays now, by your personal API token "
        + "(Authorization: Bearer cat_… or catr_…, or ?token=catr_… for a pasted URL).")
public class MeGameController {

    private final GameInfoAssembler gameInfoAssembler;
    private final ApiUserResolver userResolver;
    private final JsonMapper jsonMapper;

    @Operation(summary = "Get your channel's currently-detected game, fully aggregated",
            description = "Every platform object that could be resolved for the game your channel plays now "
                    + "(igdb/steam/xbox/dtdd/catapult, each null if unavailable), each carrying a \"more\" link "
                    + "to its own detail route. Version 3 adds the enrichment objects hltb, protondb, steamgriddb, "
                    + "steamDeck and pcgamingwiki (each null if unavailable or too slow to answer, each with its "
                    + "\"more\" link), the trailer, prices, opencritic and twitch (store prices in "
                    + "?country=, default FR; Twitch streams in ?language=), and speedrun (speedrun.com world "
                    + "records of the main categories, plus your account, personal bests and pending runs on the "
                    + "game; these three null when no speedrun.com account is found for you), stats: your own "
                    + "play time and achievements on the game on steam, xbox and retroachievements, and "
                    + "stats.blizzard: your BattleTag, region and main WoW character, StarCraft II or Diablo III "
                    + "profile (each null when it does not apply, each with a status and a \"more\" link to its "
                    + "detail), stats.bungie: your Bungie Name and Destiny 2 profile — personal data, never served "
                    + "on a public route; and publisher: the publishers' public game data (publisher.blizzard: WoW "
                    + "Token price, latest Hearthstone set, Diablo III season, in your Blizzard region else eu; "
                    + "publisher.bungie: the Destiny 2 season in progress; null when no publisher applies) — public "
                    + "game data, nothing about you.")
    @Parameter(name = "country", in = ParameterIn.QUERY, description = "Version 3 only: the ISO 3166 country the "
            + "store prices are asked for.", example = "FR", schema = @Schema(defaultValue = "FR"))
    @Parameter(name = "language", in = ParameterIn.QUERY, description = "Version 3 only: the two-letter language "
            + "of the Twitch streams counted; any language when absent.", example = "fr",
            schema = @Schema(type = "string"))
    @ApiResponse(responseCode = "200", description = "inGame=false with every platform object null while no "
            + "game is detected; otherwise the aggregated game info. The shape follows the API version: "
            + "GameInfoResponse for version 2, GameInfoV3Response (the same objects plus the enrichment ones) for "
            + "version 3.",
            content = {@Content(mediaType = "application/json",
                    schema = @Schema(anyOf = {GameInfoResponse.class, GameInfoV3Response.class})),
                    @Content(mediaType = "text/plain", schema = @Schema(type = "string",
                            description = "The one field asked with ?f=, as plain text."))})
    @GetMapping
    public ResponseEntity<?> gameInfo(
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = LANG_DESCRIPTION, example = "fr", schema = @Schema(defaultValue = "en"))
            @RequestParam(name = "lang", required = false) String lang,
            @Parameter(description = ACCEPT_LANGUAGE_DESCRIPTION, in = ParameterIn.HEADER,
                    example = "fr-FR,fr;q=0.9")
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            @Parameter(description = FIELD_DESCRIPTION, example = "igdb.url")
            @RequestParam(name = "f", required = false) String field) {
        return FieldSelector.answer(jsonMapper, gameInfo(userId, lang, acceptLanguage), null, field);
    }

    @Operation(summary = "Get a compact summary of your channel's currently-detected game",
            description = "Same aggregation as GET /api/me/game, but only inGame plus the cross-platform derived "
                    + "fields (name, description, tws, releaseDate — the first non-null value found across "
                    + "igdb/steam/xbox/dtdd/catapult, in that priority order), not the per-platform objects. Version 3 adds "
                    + "timeToBeat (HowLongToBeat's normal duration, in seconds), protonTier, deckStatus, coverUrl "
                    + "(SteamGridDB grid, else IGDB cover), trailer ({kind: mp4|youtube, url}), price (best "
                    + "store price), criticScore, liveViewers, worldRecord (speedrun.com's main category: "
                    + "{category, time in seconds, runners}) and personalBest (yours in that category, else your "
                    + "best place: {category, time, place}), playtime (your play time in minutes on the platform the "
                    + "game was detected on, else the first answering ok), achievements ({platform, unlocked, total}) "
                    + "and lastAchievement (your latest unlock on the game, any platform, blizzard included for your "
                    + "main WoW character: {platform, name, unlockedAt, rarity}). These three are personal data, "
                    + "never served on a public route.")
    @ApiResponse(responseCode = "200", description = "inGame=false while no game is detected; otherwise the "
            + "summarized game info.")
    @GetMapping("/summary")
    @JsonView(GameInfoResponse.Summary.class)
    public ResponseEntity<?> summary(
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = LANG_DESCRIPTION, example = "fr", schema = @Schema(defaultValue = "en"))
            @RequestParam(name = "lang", required = false) String lang,
            @Parameter(description = ACCEPT_LANGUAGE_DESCRIPTION, in = ParameterIn.HEADER,
                    example = "fr-FR,fr;q=0.9")
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            @Parameter(description = FIELD_DESCRIPTION, example = "name")
            @RequestParam(name = "f", required = false) String field) {
        return FieldSelector.answer(jsonMapper, gameInfo(userId, lang, acceptLanguage),
                GameInfoResponse.Summary.class, field);
    }

    // The version 3 variants. Hidden: springdoc merges both versions of a path into one operation, whose text would
    // then depend on which method it reads last; the version 2 operation tells what version 3 adds.

    @Operation(hidden = true)
    @GetMapping(version = ApiV2.VERSION_3)
    public ResponseEntity<?> gameInfoV3(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(name = "lang", required = false) String lang,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            @RequestParam(name = "country", required = false) String country,
            @RequestParam(name = "language", required = false) String language,
            @RequestParam(name = "f", required = false) String field) {
        return FieldSelector.answer(jsonMapper, gameInfoV3(userId, lang, acceptLanguage, country, language), null,
                field);
    }

    @Operation(hidden = true)
    @GetMapping(path = "/summary", version = ApiV2.VERSION_3)
    @JsonView(GameInfoResponse.Summary.class)
    public ResponseEntity<?> summaryV3(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(name = "lang", required = false) String lang,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            @RequestParam(name = "country", required = false) String country,
            @RequestParam(name = "language", required = false) String language,
            @RequestParam(name = "f", required = false) String field) {
        return FieldSelector.answer(jsonMapper, gameInfoV3(userId, lang, acceptLanguage, country, language),
                GameInfoResponse.Summary.class, field);
    }

    private ResponseEntity<GameInfoResponse> gameInfo(UUID userId, String lang, String acceptLanguage) {
        return ResponseEntity.ok(gameInfoAssembler.gameInfo(userResolver.tokenOwner(userId),
                RequestLocale.parse(lang, acceptLanguage)));
    }

    private ResponseEntity<GameInfoV3Response> gameInfoV3(UUID userId, String lang, String acceptLanguage,
                                                         String country, String language) {
        // validated before anything is resolved: a bad parameter answers 400 whatever the game
        String validCountry = country == null || country.isBlank() ? null : ApiV2.country(country);
        String validLanguage = language(language);
        return ResponseEntity.ok(gameInfoAssembler.gameInfoV3(userResolver.tokenOwner(userId),
                RequestLocale.parse(lang, acceptLanguage), validCountry, validLanguage));
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
}
