package fr.enimaloc.catapult.service.enrichment.trailer;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.service.steam.SteamStoreService;
import fr.enimaloc.catapult.service.xbox.XboxStoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The upstream HLS H.264 stream a game's MP4 trailer is remuxed from: Steam's highlight movie, else its first; the
 * Microsoft Store product's first video. The chosen URL (or its absence) is kept in the enrichment cache
 * ({@link EnrichmentSource#TRAILER}, keys {@code steam:{appId}} / {@code xbox:{productId}}), so a trailer played in a
 * loop does not call the store each time. Only a URL {@link TrailerHlsUrls} allows is ever returned.
 *
 * <p>A game is looked up as {@code GameEnrichmentAssembler} advertises it: by its Steam app id (the detected one,
 * else IGDB's Steam mapping of the game), then by its Microsoft Store product id when it was detected on Xbox.
 */
@Component
@RequiredArgsConstructor
public class TrailerSourceResolver {

    private final EnrichmentCache cache;
    private final SteamStoreService steamStoreService;
    private final XboxStoreService xboxStoreService;
    private final IgdbService igdbService;
    private final GameStateService gameStateService;

    /**
     * The HLS stream of a Steam app's trailer; empty when it has none in HLS H.264 or the source is disabled.
     *
     * @throws fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException when a fetch is needed but
     *                                                                                  {@code budget} refuses it
     */
    public Optional<URI> steam(String appId, FetchBudget budget) {
        if (!hasText(appId)) {
            return Optional.empty();
        }
        return cache.get(EnrichmentSource.TRAILER, "steam:" + appId, String.class, budget, () -> steamHls(appId))
                .flatMap(TrailerHlsUrls::parse);
    }

    /**
     * The HLS stream of a Microsoft Store product's first video; empty when it has none or the source is disabled.
     *
     * @throws fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException when a fetch is needed but
     *                                                                                  {@code budget} refuses it
     */
    public Optional<URI> xbox(String productId, FetchBudget budget) {
        if (!hasText(productId)) {
            return Optional.empty();
        }
        return cache.get(EnrichmentSource.TRAILER, "xbox:" + productId, String.class, budget,
                        () -> xboxHls(productId))
                .flatMap(TrailerHlsUrls::parse);
    }

    /** The Steam app's stream when there is one, else the Microsoft Store product's; either id may be null. */
    public Optional<URI> resolve(String steamAppId, String xboxProductId, FetchBudget budget) {
        return steam(steamAppId, budget).or(() -> xbox(xboxProductId, budget));
    }

    /** The stream of the game {@code user}'s channel plays now; empty while none is detected or it has none. */
    public Optional<URI> current(UserAccount user) {
        return gameStateService.getLastKnownGame(user).flatMap(this::forDetected);
    }

    private Optional<URI> forDetected(DetectedGame detected) {
        String xboxProductId = detected.getSourceType() == GameBinding.SourceType.XBOX
                ? detected.getSourceId() : null;
        return resolve(steamAppId(detected), xboxProductId, FetchBudget.UNLIMITED);
    }

    /** The detected Steam app id, else IGDB's Steam mapping of the game; null without either. */
    private String steamAppId(DetectedGame detected) {
        if (detected.getSourceType() == GameBinding.SourceType.STEAM && hasText(detected.getSourceId())) {
            return detected.getSourceId();
        }
        return igdbService.resolveDetected(detected.getSourceType(), detected.getSourceId(), detected.getSourceName())
                .map(IgdbService.IgdbGame::id)
                .flatMap(igdbId -> igdbService.findExternalUid(igdbId, GameBinding.SourceType.STEAM))
                .orElse(null);
    }

    private Optional<String> steamHls(String appId) {
        // movies don't depend on the store language
        return steamStoreService.fetchData(appId, Locale.ENGLISH, true)
                .map(SteamStoreService.SteamStorePage::movies)
                .flatMap(movies -> Arrays.stream(movies).filter(SteamStoreService.SteamStorePage.Movie::highlight)
                        .findFirst()
                        .or(() -> Arrays.stream(movies).findFirst()))
                .map(SteamStoreService.SteamStorePage.Movie::hlsH264)
                .filter(url -> TrailerHlsUrls.parse(url).isPresent());
    }

    private Optional<String> xboxHls(String productId) {
        return xboxStoreService.fetchProduct(productId, Locale.US)
                .map(XboxStoreService.XboxProduct::videos)
                .filter(videos -> !videos.isEmpty())
                .map(List::getFirst)
                .map(XboxStoreService.XboxVideo::hlsUrl)
                .filter(url -> TrailerHlsUrls.parse(url).isPresent());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
