package fr.enimaloc.catapult.api.userapi;

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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static fr.enimaloc.catapult.api.userapi.RequestLocale.ACCEPT_LANGUAGE_DESCRIPTION;

/**
 * One of the token owner's game bindings, Catapult's own tw/ccl configuration of a game: the target of the
 * {@code catapult} object's "more" link. Another account's binding is a 404, as an unknown one.
 */
@RestController
@RequestMapping(path = ApiV2.ME_BINDINGS_PATH, version = ApiV2.VERSION)
@RequiredArgsConstructor
@Tag(name = "Me — bindings", description = "Your game bindings (Catapult's trigger-warning and content-label "
        + "configuration of a game), by your personal API token.")
public class MeBindingsController {

    private final GameInfoAssembler gameInfoAssembler;
    private final ApiUserResolver userResolver;

    @Operation(summary = "Get one of your bindings (tw/ccl config)", description = "Lookup by GameBinding id, "
            + "as the \"more\" link on the catapult object of GET /api/me/game names it.")
    @ApiResponse(responseCode = "200", description = "The binding's tw/ccl configuration.")
    @ApiResponse(responseCode = "404", description = "No binding of yours with that id.", content = @Content)
    @GetMapping("/{bindingId}")
    public CatapultDetailResponse binding(
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "GameBinding id.", in = ParameterIn.PATH)
            @PathVariable UUID bindingId,
            @Parameter(description = "BCP 47 language tag used to localize the returned "
                    + "twLabels. Falls back to the Accept-Language request header, then English.",
                    example = "fr", schema = @Schema(defaultValue = "en"))
            @RequestParam(name = "lang", required = false) String lang,
            @Parameter(description = ACCEPT_LANGUAGE_DESCRIPTION, in = ParameterIn.HEADER,
                    example = "fr-FR,fr;q=0.9")
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        return gameInfoAssembler.catapult(bindingId, userResolver.tokenOwner(userId),
                        RequestLocale.parse(lang, acceptLanguage))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown binding"));
    }
}
