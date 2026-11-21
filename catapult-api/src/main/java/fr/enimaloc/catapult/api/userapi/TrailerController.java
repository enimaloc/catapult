package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.IpFetchBudgets;
import fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException;
import fr.enimaloc.catapult.service.enrichment.trailer.FfmpegTrailerStreamer;
import fr.enimaloc.catapult.service.enrichment.trailer.TrailerBusyException;
import fr.enimaloc.catapult.service.enrichment.trailer.TrailerSourceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.AsyncWebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * A game's trailer as a plain MP4 file (version 3): the store's HLS H.264 stream remuxed on the fly by ffmpeg, for an
 * OBS media source or a {@code <video>} tag. Nothing is stored, so every play restarts ffmpeg: no {@code Range}
 * (always the whole file, {@code 200}), and {@code HEAD} answers from the store data alone, without ffmpeg. The current
 * game's route is authenticated like {@code /api/me/game}; the per-platform ones are public, counted in the caller's
 * IP budget of upstream calls.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Trailers", description = "A game's trailer as an MP4 file, remuxed on the fly from the store's stream "
        + "(version 3).")
public class TrailerController {

    static final MediaType VIDEO_MP4 = MediaType.parseMediaType("video/mp4");
    static final String ME_TRAILER_PATH = ApiV2.ME_GAME_PATH + "/trailer.mp4";
    static final String STEAM_TRAILER_PATH = ApiV2.GAMES_PATH + "/steam/{appId}/trailer.mp4";
    static final String XBOX_TRAILER_PATH = ApiV2.GAMES_PATH + "/xbox/{productId}/trailer.mp4";
    private static final String SINCE_V3 = ApiV2.VERSION_3 + "+";
    /** Room left past ffmpeg's own limit before the servlet container gives up on the response. */
    private static final Duration ASYNC_MARGIN = Duration.ofMinutes(1);

    private final TrailerSourceResolver resolver;
    private final FfmpegTrailerStreamer streamer;
    private final IpFetchBudgets ipFetchBudgets;
    private final ApiUserResolver userResolver;

    @Operation(summary = "Stream your channel's current game's trailer as MP4",
            description = "The trailer of the game your channel plays now (Steam's highlight movie, else the "
                    + "Microsoft Store's first video), remuxed without re-encoding into a fragmented MP4. Range is "
                    + "ignored: always the whole file.")
    @ApiResponse(responseCode = "200", description = "The MP4 file.",
            content = @Content(mediaType = "video/mp4"))
    @ApiResponse(responseCode = "404", description = "No game detected, or it has no HLS H.264 trailer.",
            content = @Content)
    @ApiResponse(responseCode = "503", description = "Every ffmpeg slot is busy; see Retry-After.",
            content = @Content)
    @GetMapping(path = ME_TRAILER_PATH, version = SINCE_V3)
    public ResponseEntity<StreamingResponseBody> currentGameTrailer(@AuthenticationPrincipal UUID userId,
                                                                    NativeWebRequest request) {
        URI hls = resolver.current(userResolver.tokenOwner(userId)).orElseThrow(TrailerController::noTrailer);
        return streamed(hls, streamer.reserve(), request);
    }

    @Operation(summary = "Check your channel's current game has an MP4 trailer",
            description = "The headers GET would send, without starting ffmpeg.")
    @ApiResponse(responseCode = "200", description = "A trailer exists.", content = @Content)
    @ApiResponse(responseCode = "404", description = "No game detected, or it has no HLS H.264 trailer.",
            content = @Content)
    @RequestMapping(path = ME_TRAILER_PATH, method = RequestMethod.HEAD, version = SINCE_V3)
    public ResponseEntity<Void> currentGameTrailerHead(@AuthenticationPrincipal UUID userId) {
        return head(resolver.current(userResolver.tokenOwner(userId)));
    }

    @Operation(summary = "Stream a Steam app's trailer as MP4",
            description = "The app's highlight movie (else its first), remuxed without re-encoding into a "
                    + "fragmented MP4. Public; counts in the caller's IP budget. Range is ignored.")
    @ApiResponse(responseCode = "200", description = "The MP4 file.",
            content = @Content(mediaType = "video/mp4"))
    @ApiResponse(responseCode = "404", description = "No HLS H.264 trailer for that app.", content = @Content)
    @ApiResponse(responseCode = "429", description = "The caller's IP budget of upstream calls is spent.",
            content = @Content)
    @ApiResponse(responseCode = "503", description = "Every ffmpeg slot is busy; see Retry-After.",
            content = @Content)
    @CrossOrigin(origins = "*")
    @GetMapping(path = STEAM_TRAILER_PATH, version = SINCE_V3)
    public ResponseEntity<StreamingResponseBody> steamTrailer(
            @Parameter(description = "Steam appId.", in = ParameterIn.PATH, example = "620")
            @PathVariable @Pattern(regexp = ApiV2.STEAM_APP_ID) String appId,
            NativeWebRequest request, HttpServletRequest servletRequest) {
        FetchBudget budget = budgetOf(servletRequest);
        return publicStream(resolver.steam(appId, budget), budget, servletRequest, request);
    }

    @Operation(summary = "Check a Steam app has an MP4 trailer",
            description = "The headers GET would send, without starting ffmpeg.")
    @ApiResponse(responseCode = "200", description = "A trailer exists.", content = @Content)
    @ApiResponse(responseCode = "404", description = "No HLS H.264 trailer for that app.", content = @Content)
    @CrossOrigin(origins = "*")
    @RequestMapping(path = STEAM_TRAILER_PATH, method = RequestMethod.HEAD, version = SINCE_V3)
    public ResponseEntity<Void> steamTrailerHead(
            @Parameter(description = "Steam appId.", in = ParameterIn.PATH, example = "620")
            @PathVariable @Pattern(regexp = ApiV2.STEAM_APP_ID) String appId,
            HttpServletRequest servletRequest) {
        return head(resolver.steam(appId, budgetOf(servletRequest)));
    }

    @Operation(summary = "Stream a Microsoft Store product's trailer as MP4",
            description = "The product's first video, remuxed without re-encoding into a fragmented MP4. Public; "
                    + "counts in the caller's IP budget. Range is ignored.")
    @ApiResponse(responseCode = "200", description = "The MP4 file.",
            content = @Content(mediaType = "video/mp4"))
    @ApiResponse(responseCode = "404", description = "No HLS trailer for that product.", content = @Content)
    @ApiResponse(responseCode = "429", description = "The caller's IP budget of upstream calls is spent.",
            content = @Content)
    @ApiResponse(responseCode = "503", description = "Every ffmpeg slot is busy; see Retry-After.",
            content = @Content)
    @CrossOrigin(origins = "*")
    @GetMapping(path = XBOX_TRAILER_PATH, version = SINCE_V3)
    public ResponseEntity<StreamingResponseBody> xboxTrailer(
            @Parameter(description = "Microsoft Store product id.", in = ParameterIn.PATH, example = "9NBLGGH4R315")
            @PathVariable @Pattern(regexp = ApiV2.XBOX_PRODUCT_ID) String productId,
            NativeWebRequest request, HttpServletRequest servletRequest) {
        FetchBudget budget = budgetOf(servletRequest);
        return publicStream(resolver.xbox(productId, budget), budget, servletRequest, request);
    }

    @Operation(summary = "Check a Microsoft Store product has an MP4 trailer",
            description = "The headers GET would send, without starting ffmpeg.")
    @ApiResponse(responseCode = "200", description = "A trailer exists.", content = @Content)
    @ApiResponse(responseCode = "404", description = "No HLS trailer for that product.", content = @Content)
    @CrossOrigin(origins = "*")
    @RequestMapping(path = XBOX_TRAILER_PATH, method = RequestMethod.HEAD, version = SINCE_V3)
    public ResponseEntity<Void> xboxTrailerHead(
            @Parameter(description = "Microsoft Store product id.", in = ParameterIn.PATH, example = "9NBLGGH4R315")
            @PathVariable @Pattern(regexp = ApiV2.XBOX_PRODUCT_ID) String productId,
            HttpServletRequest servletRequest) {
        return head(resolver.xbox(productId, budgetOf(servletRequest)));
    }

    @ExceptionHandler(TrailerBusyException.class)
    public ResponseEntity<ProblemDetail> busy(TrailerBusyException e) {
        return RetryAfterProblems.answer(ErrorCode.SERVICE_UNAVAILABLE, "Every trailer slot is busy", e.retryAfter());
    }

    /**
     * A public caller's stream: it costs one call of the caller's IP budget every time, the URL cached or not (each
     * play downloads the whole upstream stream again), and takes a public slot (one per IP, never the last one).
     */
    private ResponseEntity<StreamingResponseBody> publicStream(Optional<URI> hls, FetchBudget budget,
                                                               HttpServletRequest servletRequest,
                                                               NativeWebRequest request) {
        URI source = hls.orElseThrow(TrailerController::noTrailer);
        if (!budget.tryAcquire()) {
            throw new UpstreamBudgetExceededException(budget.retryAfter());
        }
        return streamed(source, streamer.reservePublic(servletRequest.getRemoteAddr()), request);
    }

    /**
     * The MP4 of {@code source} through {@code reservation}, taken by the caller before the response starts so that a
     * busy instance can still answer 503. The slot is given back when the body ends, and when the async request
     * completes in any way (timeout, error, a body that never ran): closing twice gives it back once.
     */
    private ResponseEntity<StreamingResponseBody> streamed(URI source, FfmpegTrailerStreamer.Reservation reservation,
                                                           NativeWebRequest request) {
        try {
            // the container's default async timeout (30 s on Tomcat) would cut a trailer short
            if (request instanceof AsyncWebRequest async) {
                async.setTimeout(streamer.maxDuration().plus(ASYNC_MARGIN).toMillis());
                async.addCompletionHandler(reservation::close);
            }
        } catch (RuntimeException e) {
            reservation.close();
            throw e;
        }
        StreamingResponseBody body = out -> {
            try (reservation) {
                reservation.stream(source, out);
            }
        };
        return ResponseEntity.ok()
                .contentType(VIDEO_MP4)
                .header(HttpHeaders.ACCEPT_RANGES, "none")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    private static ResponseEntity<Void> head(Optional<URI> hls) {
        hls.orElseThrow(TrailerController::noTrailer);
        return ResponseEntity.ok()
                .contentType(VIDEO_MP4)
                .header(HttpHeaders.ACCEPT_RANGES, "none")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    private FetchBudget budgetOf(HttpServletRequest request) {
        return ipFetchBudgets.forIp(request.getRemoteAddr());
    }

    private static ResponseStatusException noTrailer() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No MP4 trailer for this game");
    }
}
