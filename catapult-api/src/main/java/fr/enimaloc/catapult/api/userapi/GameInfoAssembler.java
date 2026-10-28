package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.dtdd.DtddGameMapping;
import fr.enimaloc.catapult.domain.igdb.IgdbGameDetails;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.getter.dtdd.DtddApiClient;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import fr.enimaloc.catapult.repository.tw.TwDtddTopicMappingRepository;
import fr.enimaloc.catapult.service.account.WidgetTokenService;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.dtdd.DtddMappingService;
import fr.enimaloc.catapult.service.dtdd.DtddService;
import fr.enimaloc.catapult.service.dtdd.DtddSignalService;
import fr.enimaloc.catapult.service.igdb.IgdbGameDetailsService;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.service.link.ExternalLinks;
import fr.enimaloc.catapult.service.steam.SteamIADisclosure;
import fr.enimaloc.catapult.service.steam.SteamStoreService;
import fr.enimaloc.catapult.service.tw.TwLabelService;
import fr.enimaloc.catapult.service.xbox.XboxStoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The frozen user API's answers (v1 {@code /api/game}, v2 {@code /api/user/**}), built from the widget token or a
 * platform's own id: IGDB, Steam, Xbox, DTDD and the channel's own binding. Each answer is empty where its route
 * answers an empty 404. The "more" links follow the current request's origin ({@link PublicBaseUrl}).
 */
@Component
@RequiredArgsConstructor
public class GameInfoAssembler {

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
    private final MessageSource messageSource;

    // DTDD support is conditional on "dtdd.enabled" (see DtddService) — injected as Optional so
    // the answers degrade to dtdd=null/404 rather than failing to start when it's off.
    private final Optional<DtddMappingService> dtddMappingService;
    private final Optional<DtddSignalService> dtddSignalService;
    private final Optional<DtddService> dtddService;
    private final Optional<DtddApiClient> dtddApiClient;

    // "more" needs to be a full URL the client can follow directly, not a relative path: it follows the
    // caller's origin (the trusted proxies' X-Forwarded-*), app.base-url only outside a request.
    private final PublicBaseUrl publicBaseUrl;

    /** Which generation of routes the "more" links of an aggregated answer point at. */
    public enum LinkStyle {
        /** The frozen {@code /api/user/...} routes. */
        LEGACY,
        /** The {@code /api/games/...} and {@code /api/me/bindings/...} routes. */
        CURRENT
    }

    // ---------------------------------------------------------------- v2

    /**
     * The aggregated game info behind a widget token or dev backdoor uuid: empty for an unknown
     * token, inGame=false while the widget's channel plays nothing detected.
     */
    public Optional<GameInfoResponse> gameInfo(UUID uuid, Locale locale) {
        Optional<DetectedGame> backdoor = devBackdoorResolver.resolve(uuid);
        if (backdoor.isPresent()) {
            return Optional.of(aggregate(backdoor.get(), Optional.empty(), locale, publicBaseUrl.get(),
                    LinkStyle.LEGACY));
        }
        Optional<UserAccount> user = widgetTokenService.resolve(uuid);
        if (user.isEmpty()) {
            return Optional.empty();
        }
        String base = publicBaseUrl.get();
        return Optional.of(gameStateService.getLastKnownGame(user.get())
                .map(detected -> aggregate(detected, user, locale, base, LinkStyle.LEGACY))
                .orElseGet(() -> new GameInfoResponse(false)));
    }

    /**
     * The aggregated game info of an already authenticated user, linking to the current routes:
     * inGame=false while nothing is detected.
     */
    public GameInfoResponse gameInfo(UserAccount user, Locale locale) {
        String base = publicBaseUrl.get();
        return gameStateService.getLastKnownGame(user)
                .map(detected -> aggregate(detected, Optional.of(user), locale, base, LinkStyle.CURRENT))
                .orElseGet(() -> new GameInfoResponse(false));
    }

    /** The IGDB game's full detail, empty when IGDB has no game with that id. */
    public Optional<IgdbDetailResponse> igdb(String igdbId) {
        return igdbGameDetailsService.getDetails(igdbId).map(d -> {
            String baseUrl = publicBaseUrl.get();
            List<String> videoUrls = d.getVideoIds().stream().map(ExternalLinks::youtubeVideo).toList();
            return new IgdbDetailResponse(baseUrl, d.getSlug(),
                    ExternalLinks.igdbGame(d.getSlug()),
                    d.getSummary(), d.getStoryline(), d.getPlatforms(), d.getRating(), d.getAggregatedRating(),
                    d.getTotalRating(), d.getTotalRatingCount(), d.getFirstReleaseDate(), new HashMap<>(d.getWebsites()),
                    IgdbDetailResponse.zipRefs(d.getDlcIds(), d.getDlcNames(), baseUrl),
                    IgdbDetailResponse.zipRefs(d.getSimilarGameIds(), d.getSimilarGameNames(), baseUrl),
                    d.getGenres(), d.getGameModes(), d.getThemes(),
                    d.getPlayerPerspectives(), d.getKeywords(), d.getFranchiseNames(),
                    normalizeProtocolRelative(d.getCoverUrl()),
                    d.getScreenshotUrls().stream().map(GameInfoAssembler::normalizeProtocolRelative).toList(),
                    videoUrls);
        });
    }

    /** The Steam app's store-page detail, empty when Steam has no such app or the store fetch failed. */
    public Optional<SteamDetailResponse> steam(String appId, Locale steamLocale) {
        Optional<SteamStoreService.SteamStorePage> page = steamStoreService.fetchData(appId, steamLocale, false);
        if (page.isEmpty()) {
            return Optional.empty();
        }
        String baseUrl = publicBaseUrl.get();
        Set<String> ccls = steamStoreService.fetchCcls(List.of(appId)).getOrDefault(appId, Set.of());
        SteamStoreService.ResolvedParentApp parentApp = steamStoreService.resolveEffectiveApp(appId).orElse(null);
        GameInfoResponse.IaDisclosure ia = iaDisclosure(appId, steamLocale);
        return Optional.of(new SteamDetailResponse(ExternalLinks.steamApp(appId),
                ccls, ia, parentApp == null ? null : new SteamDetailResponse.ParentApp(baseUrl, parentApp),
                new SteamDetailResponse.Page(baseUrl, steamLocale, page.get())));
    }

    /** The Microsoft Store product, empty when there is none or the catalog fetch failed. */
    public Optional<XboxStoreService.XboxProduct> xbox(String productId, Locale locale) {
        return xboxStoreService.fetchProduct(productId, locale);
    }

    /** The title's DTDD content-warning detail, empty while DTDD is off or when nothing is known of it. */
    public Optional<DtddDetailResponse> dtdd(long dtddId) {
        if (dtddService.isEmpty()) {
            return Optional.empty();
        }
        Optional<DtddApiClient.DtddTopics> topics = dtddService.get().getTopics(dtddId);
        Optional<DtddApiClient.DtddItem> item = dtddApiClient.flatMap(c -> c.item(dtddId));
        if (topics.isEmpty() && item.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new DtddDetailResponse(
                ExternalLinks.dtddMedia(dtddId),
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

    /** The binding's tw/ccl configuration, empty when there is no binding with that id. */
    public Optional<CatapultDetailResponse> catapult(UUID bindingId, Locale locale) {
        return gameBindingRepository.findById(bindingId).map(b -> catapultDetail(b, locale));
    }

    /** The owner's binding tw/ccl configuration, empty when there is none with that id or it is not theirs. */
    public Optional<CatapultDetailResponse> catapult(UUID bindingId, UserAccount owner, Locale locale) {
        return gameBindingRepository.findById(bindingId)
                .filter(b -> b.getUser() != null && b.getUser().getId() != null
                        && b.getUser().getId().equals(owner.getId()))
                .map(b -> catapultDetail(b, locale));
    }

    private CatapultDetailResponse catapultDetail(GameBinding b, Locale locale) {
        return new CatapultDetailResponse(b.getCreatedAt(), b.getUpdatedAt(), b.getSourceType(),
                        b.getSourceId(), b.getSourceName(), b.getTwitchGameId(), b.getTwitchGameName(), b.isIgnored(),
                        b.isCclEnabled(), b.getCcls(), b.isTwEnabled(), b.isTwOverride(), b.getTws(),
                        localizeTws(b.getTws(), locale));
    }

    /** Every platform object resolvable for {@code detected}, each null when unavailable. */
    private GameInfoResponse aggregate(DetectedGame detected, Optional<UserAccount> user, Locale locale,
                                       String base, LinkStyle style) {
        Optional<String> igdbId = resolveIgdbId(detected);
        return new GameInfoResponse(true,
                igdbId.flatMap(id -> igdbObject(id, base, style)).orElse(null),
                steamObject(detected, locale, base, style),
                xboxObject(detected, locale, base, style),
                resolveDtdd(detected, igdbId, locale, base, style),
                user.flatMap(u -> catapultObject(u, detected, locale, base, style)).orElse(null),
                detected.getSourceName());
    }

    private Optional<GameInfoResponse.IgdbObject> igdbObject(String igdbId, String base, LinkStyle style) {
        return igdbGameDetailsService.getDetails(igdbId).map(details -> new GameInfoResponse.IgdbObject(igdbId,
                details.getSlug(), details.getSummary(), normalizeProtocolRelative(details.getCoverUrl()),
                details.getGenres(), moreUrl(base, style, "/igdb/" + igdbId), details.getFirstReleaseDate()));
    }

    private GameInfoResponse.SteamObject steamObject(DetectedGame detected, Locale locale, String base,
                                                   LinkStyle style) {
        if (detected.getSourceType() != GameBinding.SourceType.STEAM || detected.getSourceId() == null) {
            return null;
        }
        String appId = detected.getSourceId();
        return steamStoreService.fetchData(appId, locale)
                .map(page -> new GameInfoResponse.SteamObject(appId, page.name(), page.shortDescription(),
                        page.categories(), page.developers(), page.legalNotice(), page.headerImage(),
                        page.releaseDate(), page.contentDescriptors(), iaDisclosure(appId, locale), locale,
                        moreUrl(base, style, "/steam/" + appId)))
                .orElse(null);
    }

    private GameInfoResponse.XboxObject xboxObject(DetectedGame detected, Locale locale, String base,
                                                   LinkStyle style) {
        if (detected.getSourceType() != GameBinding.SourceType.XBOX || detected.getSourceId() == null) {
            return null;
        }
        String productId = detected.getSourceId();
        return xboxStoreService.fetchProduct(productId, locale)
                .map(p -> new GameInfoResponse.XboxObject(productId, p.title(), p.description(), p.publisherName(),
                        p.developerName(), p.coverImage(), p.storeUrl(), moreUrl(base, style, "/xbox/" + productId)))
                .orElse(null);
    }

    /** The widget channel's own tw/ccl config for the game, when it has a binding for it. */
    private Optional<GameInfoResponse.CatapultObject> catapultObject(UserAccount user, DetectedGame detected,
                                                                     Locale locale, String base,
                                                                     LinkStyle style) {
        return gameBindingRepository.findByUserAndSourceIdAndSourceType(user, detected.getSourceId(), detected.getSourceType())
                .map(binding -> new GameInfoResponse.CatapultObject(binding.getId(), binding.getCreatedAt(),
                        binding.getTws(), binding.getCcls(), moreUrl(base, style, "/catapult/" + binding.getId()),
                        localizeTws(binding.getTws(), locale)));
    }

    /** Whether the Steam store page declares AI-generated content, and the developer's note about it. */
    private GameInfoResponse.IaDisclosure iaDisclosure(String appId, Locale locale) {
        return steamStoreService.fetchIADisclosure(appId, locale)
                .map(html -> {
                    boolean hasDisclosure = SteamIADisclosure.hasDisclosure(html);
                    String note = hasDisclosure ? SteamIADisclosure.extractDeveloperDescription(html).orElse(null) : null;
                    return new GameInfoResponse.IaDisclosure(hasDisclosure, note);
                })
                .orElse(new GameInfoResponse.IaDisclosure(false, null));
    }

    private GameInfoResponse.DtddObject resolveDtdd(DetectedGame detected, Optional<String> igdbId, Locale locale,
                                                   String base, LinkStyle style) {
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
                ExternalLinks.dtddMedia(mapping.getDtddId()), twIds, localizeTws(twIds, locale),
                moreUrl(base, style, "/dtdd/" + mapping.getDtddId()));
    }

    // IGDB image URLs (cover, screenshots) come back protocol-relative ("//images.igdb.com/...").
    // The persist path (IgdbGameDetailsService) normalizes this for freshly-fetched entries, but
    // an already-cached entry can still carry the old raw form until its TTL forces a refresh —
    // normalize defensively here too so a stale cache row doesn't leak a broken URL to callers.
    private static String normalizeProtocolRelative(String url) {
        return url != null && url.startsWith("//") ? "https:" + url : url;
    }

    /** {@code path} is the legacy one ({@code /igdb/{id}}, ..., {@code /catapult/{id}}); the current style remaps it. */
    private static String moreUrl(String base, LinkStyle style, String path) {
        if (style == LinkStyle.LEGACY) {
            return base + ApiV2.PATH + path;
        }
        return path.startsWith("/catapult/")
                ? base + ApiV2.ME_BINDINGS_PATH + path.substring("/catapult".length())
                : base + ApiV2.GAMES_PATH + path;
    }

    // ---------------------------------------------------------------- v1 (frozen schema)

    /** The v1 summary of the widget's detected game, empty for an unknown token or while no game is detected. */
    public Optional<UserApiV1Controller.GameInfoResponse> legacyGameInfo(UUID uuid, Locale locale) {
        Optional<UserAccount> user = widgetTokenService.resolve(uuid);
        if (user.isEmpty()) {
            return Optional.empty();
        }
        return gameStateService.getLastKnownGame(user.get())
                .map(detected -> legacyResponse(user.get(), detected, locale));
    }

    private UserApiV1Controller.GameInfoResponse legacyResponse(UserAccount user, DetectedGame detected, Locale locale) {
        GameBinding binding = gameBindingRepository
                .findByUserAndSourceIdAndSourceType(user, detected.getSourceId(), detected.getSourceType())
                .orElse(null);

        Optional<IgdbGameDetails> details = resolveIgdbId(detected).flatMap(igdbGameDetailsService::getDetails);
        Set<String> tws = localizeTws(binding == null ? Set.of() : binding.getTws(), locale);
        Set<String> ccls = binding == null ? Set.of() : binding.getCcls();
        List<String> platforms = details.map(IgdbGameDetails::getPlatforms).orElse(List.of());

        return new UserApiV1Controller.GameInfoResponse(
                detected.getSourceName(),
                details.map(IgdbGameDetails::getSlug)
                        .map(ExternalLinks::igdbGame)
                        .orElse(null),
                detected.getSourceType(),
                storeName(detected.getSourceType(), locale),
                storeUrl(detected, details.orElse(null)),
                description(detected, locale, details).orElse(null),
                binding == null ? null : binding.getTwitchGameId(),
                binding == null ? null : binding.getTwitchGameName(),
                tws,
                joinSorted(tws),
                ccls,
                joinSorted(ccls),
                platforms,
                String.join(", ", platforms),
                details.map(IgdbGameDetails::getRating).orElse(null),
                details.map(IgdbGameDetails::getAggregatedRating).orElse(null),
                details.map(IgdbGameDetails::getFirstReleaseDate).orElse(null),
                UserApiV1Controller.VERSION);
    }

    // The store's own description takes priority (locale-matched to the "lang" param), since it's
    // written for that specific release/platform; IGDB's summary is a generic fallback for stores
    // with no description API (or Steam with none in the requested language) so the field is
    // rarely empty. Only Steam has a description API integrated today.
    private Optional<String> description(DetectedGame detected, Locale locale, Optional<IgdbGameDetails> details) {
        Optional<String> storeDescription = detected.getSourceType() == GameBinding.SourceType.STEAM
                && detected.getSourceId() != null
                ? steamStoreService.fetchDescription(detected.getSourceId(), locale)
                : Optional.empty();
        return storeDescription.or(() -> details.map(IgdbGameDetails::getSummary));
    }

    // tws/ccls are unordered Sets, so sort for a deterministic display string;
    // platforms is already an ordered List from IGDB and is joined as-is.
    private static String joinSorted(Set<String> values) {
        return values.stream().sorted().collect(Collectors.joining(", "));
    }

    private String storeName(GameBinding.SourceType sourceType, Locale locale) {
        return messageSource.getMessage("game_info.store." + sourceType.name(), null, locale);
    }

    // IGDB's own "steam" website category is unreliable (see IgdbWebsiteKeys#links —
    // external_games can overwrite it with a bare app id
    // instead of a URL), so Steam gets a store.steampowered.com URL built directly
    // from the detected app id rather than trusting the cached website map.
    private static String storeUrl(DetectedGame detected, IgdbGameDetails details) {
        if (detected.getSourceType() == GameBinding.SourceType.STEAM && detected.getSourceId() != null) {
            return ExternalLinks.steamApp(detected.getSourceId());
        }
        return details == null ? null : details.getWebsites().get(storeWebsiteKey(detected.getSourceType()));
    }

    private static String storeWebsiteKey(GameBinding.SourceType sourceType) {
        return switch (sourceType) {
            case STEAM -> "steam";
            case BATTLENET -> "battlenet";
            default -> "";
        };
    }

    // ---------------------------------------------------------------- shared

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
