package fr.enimaloc.catapult.api.userapi;

import com.fasterxml.jackson.annotation.JsonView;
import fr.enimaloc.catapult.domain.DtddGameMapping;
import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.IgdbGameDetails;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.getter.DtddApiClient;
import fr.enimaloc.catapult.repository.GameBindingRepository;
import fr.enimaloc.catapult.repository.TwDtddTopicMappingRepository;
import fr.enimaloc.catapult.service.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

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

    private final WidgetTokenService widgetTokenService;
    private final GameStateService gameStateService;
    private final GameBindingRepository gameBindingRepository;
    private final IgdbService igdbService;
    private final IgdbGameDetailsService igdbGameDetailsService;
    private final SteamStoreService steamStoreService;
    private final XboxStoreService xboxStoreService;
    private final TwLabelService twLabelService;
    private final TwDtddTopicMappingRepository twDtddTopicMappingRepository;
    private final DevBackdoorResolver devBackdoorResolver;

    // DTDD support is conditional on "dtdd.enabled" (see DtddService) — injected as Optional so
    // this controller degrades to dtdd=null/404 rather than failing to start when it's off.
    private final Optional<DtddMappingService> dtddMappingService;
    private final Optional<DtddSignalService> dtddSignalService;
    private final Optional<DtddService> dtddService;
    private final Optional<DtddApiClient> dtddApiClient;

    // Same convention as ApiSteamConnectController for building absolute URLs behind the reverse
    // proxy — "more" needs to be a full URL the client can follow directly, not a relative path.
    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

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
    public ResponseEntity<GameInfoResponse> gameInfo(
            @Parameter(description = "Per-user widget token, or a dev/test backdoor UUID (see "
                    + "DevBackdoorResolver) — the sole access control for this public endpoint.",
                    in = ParameterIn.PATH, example = ApiV2.GENERIC_UUID_EXAMPLE,
                    schema = @Schema(defaultValue = ApiV2.GENERIC_UUID_EXAMPLE))
            @PathVariable UUID uuid,
            @Parameter(description = LANG_DESCRIPTION, example = "fr", schema = @Schema(defaultValue = "en"))
            @RequestParam(name = "lang", required = false) String lang,
            @Parameter(description = ACCEPT_LANGUAGE_DESCRIPTION, in = ParameterIn.HEADER, example = "fr-FR,fr;q=0.9")
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        Optional<DetectedGame> detectedTmp = devBackdoorResolver.resolve(uuid);
        Optional<UserAccount> user = Optional.empty();
        if (detectedTmp.isEmpty()) {
            user = widgetTokenService.resolve(uuid);
            if (user.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            detectedTmp = gameStateService.getLastKnownGame(user.get());
            if (detectedTmp.isEmpty()) {
                return ResponseEntity.ok(new GameInfoResponse(false));
            }
        }
        return ResponseEntity.ok(getGIR(lang, acceptLanguage, detectedTmp, user));
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
    public ResponseEntity<GameInfoResponse> summary(
            @Parameter(description = "Per-user widget token, or a dev/test backdoor UUID (see "
                    + "DevBackdoorResolver) — the sole access control for this public endpoint.",
                    in = ParameterIn.PATH, example = ApiV2.GENERIC_UUID_EXAMPLE,
                    schema = @Schema(defaultValue = ApiV2.GENERIC_UUID_EXAMPLE))
            @PathVariable UUID uuid,
            @Parameter(description = LANG_DESCRIPTION, example = "fr", schema = @Schema(defaultValue = "en"))
            @RequestParam(name = "lang", required = false) String lang,
            @Parameter(description = ACCEPT_LANGUAGE_DESCRIPTION, in = ParameterIn.HEADER, example = "fr-FR,fr;q=0.9")
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        Optional<DetectedGame> detectedTmp = devBackdoorResolver.resolve(uuid);
        Optional<UserAccount> user = Optional.empty();
        if (detectedTmp.isEmpty()) {
            user = widgetTokenService.resolve(uuid);
            if (user.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            detectedTmp = gameStateService.getLastKnownGame(user.get());
            if (detectedTmp.isEmpty()) {
                return ResponseEntity.ok(new GameInfoResponse(false));
            }
        }
        return ResponseEntity.ok(getGIR(lang, acceptLanguage, detectedTmp, user));
    }

    private @NonNull GameInfoResponse getGIR(String lang, String acceptLanguage, Optional<DetectedGame> detected,
                                             Optional<UserAccount> user) {
        if (detected.isEmpty()) return new GameInfoResponse(false);
        Locale locale = parseLocale(lang, acceptLanguage);
        Optional<String> igdbId = resolveIgdbId(detected.get());
        Optional<IgdbGameDetails> igdbGameDetails = igdbId.flatMap(igdbGameDetailsService::getDetails);
        GameInfoResponse.IgdbObject igdb = null;
        if (igdbGameDetails.isPresent()) {
            IgdbGameDetails gameDetails = igdbGameDetails.get();
            igdb = new GameInfoResponse.IgdbObject(igdbId.get(), gameDetails.getSlug(), gameDetails.getSummary(),
                    normalizeProtocolRelative(gameDetails.getCoverUrl()), gameDetails.getGenres(),
                    moreUrl("/igdb/" + igdbId.get()));
        }

        GameInfoResponse.SteamObject steam = null;
        if (detected.get().getSourceType() == GameBinding.SourceType.STEAM && detected.get().getSourceId() != null) {
            Optional<SteamStoreService.SteamStorePage> pageOpt = steamStoreService.fetchData(detected.get().getSourceId(), locale);
            if (pageOpt.isPresent()) {
                SteamStoreService.SteamStorePage page = pageOpt.get();
                steam = new GameInfoResponse.SteamObject(detected.get().getSourceId(), page.name(), page.shortDescription(),
                        page.categories(), page.developers(), page.legalNotice(), page.headerImage(),
                        page.releaseDate(), page.contentDescriptors(), locale,
                        moreUrl("/steam/" + detected.get().getSourceId()));
            }
        }

        GameInfoResponse.XboxObject xbox = null;
        if (detected.get().getSourceType() == GameBinding.SourceType.XBOX && detected.get().getSourceId() != null) {
            Optional<XboxStoreService.XboxProduct> product = xboxStoreService.fetchProduct(detected.get().getSourceId(), locale);
            if (product.isPresent()) {
                XboxStoreService.XboxProduct p = product.get();
                xbox = new GameInfoResponse.XboxObject(detected.get().getSourceId(), p.title(), p.description(),
                        p.publisherName(), p.developerName(), p.coverImage(), p.storeUrl(),
                        moreUrl("/xbox/" + detected.get().getSourceId()));
            }
        }

        GameInfoResponse.DtddObject dtdd = resolveDtdd(detected.get(), igdbId, locale);

        Optional<GameBinding> bindingOpt = user.flatMap(u -> gameBindingRepository.findByUserAndSourceIdAndSourceType(u, detected.get().getSourceId(), detected.get().getSourceType()));
        GameInfoResponse.CatapultObject catapult = null;
        if (bindingOpt.isPresent()) {
            GameBinding binding = bindingOpt.get();
            catapult = new GameInfoResponse.CatapultObject(binding.getId(), binding.getCreatedAt(), binding.getTws(),
                    binding.getCcls(), moreUrl("/catapult/" + binding.getId()));
        }
        GameInfoResponse body = new GameInfoResponse(true, igdb, steam, xbox, dtdd, catapult);
        return body;
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
        Optional<IgdbGameDetails> details = igdbGameDetailsService.getDetails(igdbId);
        if (details.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        IgdbGameDetails d = details.get();
        List<String> videoUrls = d.getVideoIds().stream().map(id -> "https://www.youtube.com/watch?v=" + id).toList();
        return ResponseEntity.ok(new IgdbDetailResponse(baseUrl, d.getSlug(),
                "https://www.igdb.com/games/" + d.getSlug(),
                d.getSummary(), d.getStoryline(), d.getPlatforms(), d.getRating(), d.getAggregatedRating(),
                d.getTotalRating(), d.getTotalRatingCount(), d.getFirstReleaseDate(), new HashMap<>(d.getWebsites()),
                IgdbDetailResponse.zipRefs(d.getDlcIds(), d.getDlcNames(), baseUrl),
                IgdbDetailResponse.zipRefs(d.getSimilarGameIds(), d.getSimilarGameNames(), baseUrl),
                d.getGenres(), d.getGameModes(), d.getThemes(),
                d.getPlayerPerspectives(), d.getKeywords(), d.getFranchiseNames(),
                normalizeProtocolRelative(d.getCoverUrl()),
                d.getScreenshotUrls().stream().map(UserApiV2Controller::normalizeProtocolRelative).toList(),
                videoUrls));
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
        Locale steamLocale = parseLocale(lang, acceptLanguage);
        Optional<SteamStoreService.SteamStorePage> page = steamStoreService.fetchData(appId, steamLocale, false);
        if (page.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Set<String> ccls = steamStoreService.fetchCcls(List.of(appId)).getOrDefault(appId, Set.of());
        SteamStoreService.ResolvedParentApp parentApp = steamStoreService.resolveEffectiveApp(appId).orElse(null);
        return ResponseEntity.ok(new SteamDetailResponse("https://store.steampowered.com/app/" + appId,
                ccls, parentApp == null ? null : new SteamDetailResponse.ParentApp(baseUrl, parentApp),
                new SteamDetailResponse.Page(baseUrl, steamLocale, page.get())));
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
        Optional<XboxStoreService.XboxProduct> product = xboxStoreService.fetchProduct(productId, parseLocale(lang, acceptLanguage));
        if (product.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(product.get());
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
        if (dtddService.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Optional<DtddApiClient.DtddTopics> topics = dtddService.get().getTopics(dtddId);
        Optional<DtddApiClient.DtddItem> item = dtddApiClient.flatMap(c -> c.item(dtddId));
        if (topics.isEmpty() && item.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new DtddDetailResponse(
                "https://www.doesthedogdie.com/media/" + dtddId,
                topics.map(DtddApiClient.DtddTopics::yesTopics).orElse(List.of()),
                topics.map(DtddApiClient.DtddTopics::noTopics).orElse(List.of()),
                topics.map(DtddApiClient.DtddTopics::mostlyTopics).orElse(List.of()),
                item.map(DtddApiClient.DtddItem::overview).orElse(null),
                item.map(DtddApiClient.DtddItem::genres).orElse(null),
                item.map(DtddApiClient.DtddItem::releaseYear).orElse(0L),
                item.map(DtddApiClient.DtddItem::itemTypeName).orElse(null),
                item.map(DtddApiClient.DtddItem::tmdbId).orElse(0L),
                item.map(DtddApiClient.DtddItem::imdbId).orElse(null),
                item.map(DtddApiClient.DtddItem::posterImage).orElse(null),
                item.map(DtddApiClient.DtddItem::backgroundImage).orElse(null),
                item.map(DtddApiClient.DtddItem::topicItemStats).orElse(null)));
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
        Optional<GameBinding> binding = gameBindingRepository.findById(bindingId);
        if (binding.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        GameBinding b = binding.get();
        return ResponseEntity.ok(new CatapultDetailResponse(b.getCreatedAt(), b.getUpdatedAt(), b.getSourceType(),
                b.getSourceId(), b.getSourceName(), b.getTwitchGameId(), b.getTwitchGameName(), b.isIgnored(),
                b.isCclEnabled(), b.getCcls(), b.isTwEnabled(), b.isTwOverride(), b.getTws(),
                localizeTws(b.getTws(), parseLocale(lang, acceptLanguage))));
    }

    private GameInfoResponse.DtddObject resolveDtdd(DetectedGame detected, Optional<String> igdbId, Locale locale) {
        if (dtddMappingService.isEmpty() || dtddSignalService.isEmpty() || igdbId.isEmpty()) {
            return null;
        }
        DtddGameMapping mapping = dtddMappingService.get().resolve(igdbId.get(), detected.getSourceName());
        if (mapping.getDtddId() == null) {
            return null;
        }
        Set<String> topics = dtddSignalService.get().getYesMostlyTopics(igdbId.get(), detected.getSourceName());
        Set<String> twIds = topics.isEmpty() ? Set.of() : twDtddTopicMappingRepository.findTwIdsByDtddTopicNameInIgnoreCase(
                topics.stream().map(t -> t.toLowerCase(Locale.ROOT)).collect(Collectors.toSet()));
        return new GameInfoResponse.DtddObject(mapping.getDtddId(),
                "https://www.doesthedogdie.com/media/" + mapping.getDtddId(), twIds, localizeTws(twIds, locale),
                moreUrl("/dtdd/" + mapping.getDtddId()));
    }

    // IGDB image URLs (cover, screenshots) come back protocol-relative ("//images.igdb.com/...").
    // The persist path (IgdbGameDetailsService) normalizes this for freshly-fetched entries, but
    // an already-cached entry can still carry the old raw form until its TTL forces a refresh —
    // normalize defensively here too so a stale cache row doesn't leak a broken URL to callers.
    private static String normalizeProtocolRelative(String url) {
        return url != null && url.startsWith("//") ? "https:" + url : url;
    }

    private String moreUrl(String path) {
        return baseUrl + ApiV2.PATH + path;
    }

    // "lang" wins when present (it's the mechanism that works from a plain pasted OBS URL, with
    // no header control); Accept-Language is the fallback for clients that do set headers. A
    // request with neither, or an unparseable Accept-Language value, gets English — deliberately
    // not the JVM/server default locale, which would make behavior depend on the deploy
    // environment instead of being a documented, stable contract.
    private static Locale parseLocale(String lang, String acceptLanguage) {
        if (lang != null && !lang.isBlank()) {
            return Locale.forLanguageTag(lang);
        }
        if (acceptLanguage != null && !acceptLanguage.isBlank()) {
            try {
                List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(acceptLanguage);
                if (!ranges.isEmpty()) {
                    return Locale.forLanguageTag(ranges.get(0).getRange());
                }
            } catch (IllegalArgumentException ignored) {
                // malformed Accept-Language header — fall through to the default
            }
        }
        return Locale.ENGLISH;
    }

    private Set<String> localizeTws(Set<String> tws, Locale locale) {
        return tws.stream().map(id -> twLabelService.resolve(id, locale)).collect(Collectors.toSet());
    }

    private Optional<String> resolveIgdbId(DetectedGame detected) {
        return (detected.getSourceId() != null
                ? igdbService.findByExternalAppId(detected.getSourceType(), detected.getSourceId())
                .or(() -> igdbService.findByName(detected.getSourceName()))
                : igdbService.findByName(detected.getSourceName()))
                .map(IgdbService.IgdbGame::id);
    }
}
