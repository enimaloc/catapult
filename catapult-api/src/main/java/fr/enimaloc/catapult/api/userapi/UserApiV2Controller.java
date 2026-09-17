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
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@RestController
@RequestMapping(value = {"/api/v2", "/api/latest"})
public class UserApiV2Controller {

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
    @CrossOrigin(origins = "*")
    @GetMapping("/game/{uuid}")
    public ResponseEntity<GameInfoResponse> gameInfo(@PathVariable UUID uuid,
                                                     @RequestParam(name = "lang", required = false) String lang) {
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
        return ResponseEntity.ok(getGIR(lang, detectedTmp, user));
    }

    @CrossOrigin(origins = "*")
    @GetMapping("/summary/{uuid}")
    @JsonView(GameInfoResponse.Summary.class)
    public ResponseEntity<GameInfoResponse> summary(@PathVariable UUID uuid,
                                                     @RequestParam(name = "lang", required = false) String lang) {
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
        return ResponseEntity.ok(getGIR(lang, detectedTmp, user));
    }

    private @NonNull GameInfoResponse getGIR(String lang, Optional<DetectedGame> detected, Optional<UserAccount> user) {
        if (detected.isEmpty()) return new GameInfoResponse(false);
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
            Locale steamLocale = parseLocale(lang);
            Optional<SteamStoreService.SteamStorePage> pageOpt = steamStoreService.fetchData(detected.get().getSourceId(), steamLocale);
            if (pageOpt.isPresent()) {
                SteamStoreService.SteamStorePage page = pageOpt.get();
                steam = new GameInfoResponse.SteamObject(detected.get().getSourceId(), page.name(), page.shortDescription(),
                        page.categories(), page.developers(), page.legalNotice(), page.headerImage(),
                        page.releaseDate(), page.contentDescriptors(), steamLocale,
                        moreUrl("/steam/" + detected.get().getSourceId()));
            }
        }

        GameInfoResponse.XboxObject xbox = null;
        if (detected.get().getSourceType() == GameBinding.SourceType.XBOX && detected.get().getSourceId() != null) {
            Optional<XboxStoreService.XboxProduct> product = xboxStoreService.fetchProduct(detected.get().getSourceId(), parseLocale(lang));
            if (product.isPresent()) {
                XboxStoreService.XboxProduct p = product.get();
                xbox = new GameInfoResponse.XboxObject(detected.get().getSourceId(), p.title(), p.description(),
                        p.publisherName(), p.developerName(), p.coverImage(), p.storeUrl(),
                        moreUrl("/xbox/" + detected.get().getSourceId()));
            }
        }

        GameInfoResponse.DtddObject dtdd = resolveDtdd(detected.get(), igdbId, parseLocale(lang));

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
    @GetMapping("/igdb/{igdbId}")
    public ResponseEntity<IgdbDetailResponse> igdbDetail(@PathVariable String igdbId) {
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

    @GetMapping("/steam/{appId}")
    public ResponseEntity<SteamDetailResponse> steamDetail(@PathVariable String appId,
                                                            @RequestParam(name = "lang", required = false) String lang) {
        Locale steamLocale = parseLocale(lang);
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

    @GetMapping("/xbox/{productId}")
    public ResponseEntity<XboxStoreService.XboxProduct> xboxDetail(@PathVariable String productId,
                                                                    @RequestParam(name = "lang", required = false) String lang) {
        Optional<XboxStoreService.XboxProduct> product = xboxStoreService.fetchProduct(productId, parseLocale(lang));
        if (product.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(product.get());
    }

    @GetMapping("/dtdd/{dtddId}")
    public ResponseEntity<DtddDetailResponse> dtddDetail(@PathVariable long dtddId) {
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
    @GetMapping("/catapult/{bindingId}")
    public ResponseEntity<CatapultDetailResponse> catapultDetail(@PathVariable UUID bindingId,
                                                                  @RequestParam(name = "lang", required = false) String lang) {
        Optional<GameBinding> binding = gameBindingRepository.findById(bindingId);
        if (binding.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        GameBinding b = binding.get();
        return ResponseEntity.ok(new CatapultDetailResponse(b.getCreatedAt(), b.getUpdatedAt(), b.getSourceType(),
                b.getSourceId(), b.getSourceName(), b.getTwitchGameId(), b.getTwitchGameName(), b.isIgnored(),
                b.isCclEnabled(), b.getCcls(), b.isTwEnabled(), b.isTwOverride(), b.getTws(),
                localizeTws(b.getTws(), parseLocale(lang))));
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
        return baseUrl + "/api/v" + ApiV2.VERSION + path;
    }

    private static Locale parseLocale(String lang) {
        return lang == null || lang.isBlank() ? Locale.ENGLISH : Locale.forLanguageTag(lang);
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
