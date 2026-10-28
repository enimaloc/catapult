package fr.enimaloc.catapult.api.userapi;

import com.fasterxml.jackson.annotation.JsonView;
import fr.enimaloc.catapult.api.ApiUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static fr.enimaloc.catapult.api.userapi.FieldSelector.FIELD_DESCRIPTION;
import static fr.enimaloc.catapult.api.userapi.RequestLocale.ACCEPT_LANGUAGE_DESCRIPTION;
import static fr.enimaloc.catapult.api.userapi.RequestLocale.LANG_DESCRIPTION;

/**
 * The game the token owner's channel plays now, aggregated across every platform, or its summary: for overlays and
 * chat bots, authenticated by the personal API token (the read-only one opens it, from {@code ?token=} too). With
 * {@code f}, one field of the answer as plain text.
 */
@RestController
@RequestMapping(path = ApiV2.ME_GAME_PATH, version = ApiV2.VERSION)
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
                    + "to its own detail route.")
    @ApiResponse(responseCode = "200", description = "inGame=false with every platform object null while no "
            + "game is detected; otherwise the aggregated game info.")
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
                    + "igdb/steam/xbox/dtdd/catapult, in that priority order), not the per-platform objects.")
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

    private ResponseEntity<GameInfoResponse> gameInfo(UUID userId, String lang, String acceptLanguage) {
        return ResponseEntity.ok(gameInfoAssembler.gameInfo(userResolver.tokenOwner(userId),
                RequestLocale.parse(lang, acceptLanguage)));
    }
}
