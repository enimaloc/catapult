package fr.enimaloc.catapult.service.enrichment.faceit;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.faceit.FaceitAccount;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.getter.faceit.FaceitApiKeyRotator;
import fr.enimaloc.catapult.repository.faceit.FaceitAccountRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.faceit.FaceitModels.GameProfile;
import fr.enimaloc.catapult.service.enrichment.faceit.FaceitModels.LastMatch;
import fr.enimaloc.catapult.service.enrichment.faceit.FaceitModels.Lifetime;
import fr.enimaloc.catapult.service.enrichment.faceit.FaceitModels.Player;
import fr.enimaloc.catapult.service.enrichment.faceit.FaceitStats.Cs2Stats;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.FaceitGame;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A member's personal stats on a FACEIT game (Counter-Strike 2: skill level, Elo, lifetime numbers, last match),
 * through the shared cache under {@code u:<uuid>:...} keys, by the player id of their declared nickname. A player
 * FACEIT does not know (or one without a CS2 profile) is cached ({@code not_found}); an outage (any failure of the
 * fetch) is never cached and says {@code unavailable}. The source off (disabled, or no key in the pool) or no
 * declared nickname gives no object at all.
 */
@Service
public class FaceitStatsService {

    /** A player, or null for one FACEIT does not know (cached as a value, under the source's TTL). */
    public record PlayerCell(Player value) {
    }

    /** Lifetime stats, or null when the player has none on the game. */
    public record LifetimeCell(Lifetime value) {
    }

    /** The last match, or null when the player played none. */
    public record LastMatchCell(LastMatch value) {
    }

    /** A cached value, or the fact that the upstream call failed (an outage, as opposed to "nothing there"). */
    private record Looked<T>(Optional<T> value, boolean failed) {
    }

    private final EnrichmentCache cache;
    private final FaceitClient client;
    private final FaceitApiKeyRotator keys;
    private final FaceitAccountRepository accounts;
    private final EnrichmentProperties properties;

    public FaceitStatsService(EnrichmentCache cache, FaceitClient client, FaceitApiKeyRotator keys,
                              FaceitAccountRepository accounts, EnrichmentProperties properties) {
        this.cache = cache;
        this.client = client;
        this.keys = keys;
        this.accounts = accounts;
        this.properties = properties;
    }

    /** Whether FACEIT can be asked at all: enabled, with a key in the shared pool. */
    public boolean available() {
        return properties.faceitEnabled() && keys.hasKeys();
    }

    /** The member's stats on the game being played; empty when it is no FACEIT game. */
    public Optional<FaceitStats> forDetected(UserAccount user, DetectedGame detected, String igdbId,
                                             FetchBudget budget) {
        return PublisherGames.faceit(detected, igdbId, properties.getFaceit().getCs2IgdbId())
                .flatMap(game -> forGame(user, game, budget, false));
    }

    /**
     * The member's stats on {@code game}; empty when there is nothing to say (the source is off, no key in the
     * pool, no nickname declared). {@code detail} also fills the recent results.
     *
     * @throws fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException when {@code budget} refuses
     *         a call that is needed
     */
    public Optional<FaceitStats> forGame(UserAccount user, FaceitGame game, FetchBudget budget, boolean detail) {
        if (!available() || !properties.source(EnrichmentSource.FACEIT_PLAYER).enabled()) {
            return Optional.empty();
        }
        Optional<FaceitAccount> row = accounts.findById(user.getId());
        if (row.isEmpty()) {
            return Optional.empty();
        }
        FaceitAccount account = row.get();
        String playerId = account.getPlayerId();
        String key = "u:" + user.getId() + ":faceit:" + playerId;
        Looked<Player> player = look(EnrichmentSource.FACEIT_PLAYER, key, PlayerCell.class, PlayerCell::new,
                PlayerCell::value, budget, () -> client.player(playerId));
        if (player.value().isEmpty()) {
            Status status = player.failed() ? Status.UNAVAILABLE : Status.NOT_FOUND;
            return Optional.of(new FaceitStats(status, game, account.getNickname(), Cs2Stats.of(status), null));
        }
        Player found = player.value().get();
        GameProfile profile = found.cs2();
        if (profile == null) {
            return Optional.of(new FaceitStats(Status.NOT_FOUND, game, found.nickname(),
                    Cs2Stats.of(Status.NOT_FOUND), null));
        }
        // The lifetime numbers and the last match are parts: without them the profile is still served
        String gameKey = key + ":" + game.faceitId();
        Lifetime lifetime = null;
        if (properties.source(EnrichmentSource.FACEIT_STATS).enabled()) {
            lifetime = look(EnrichmentSource.FACEIT_STATS, gameKey, LifetimeCell.class, LifetimeCell::new,
                    LifetimeCell::value, budget, () -> client.lifetime(playerId, game.faceitId())).value()
                    .orElse(null);
        }
        LastMatch last = null;
        if (properties.source(EnrichmentSource.FACEIT_LAST_MATCH).enabled()) {
            last = look(EnrichmentSource.FACEIT_LAST_MATCH, gameKey, LastMatchCell.class, LastMatchCell::new,
                    LastMatchCell::value, budget, () -> client.lastMatch(playerId, game.faceitId()).orElse(null))
                    .value().orElse(null);
        }
        Cs2Stats cs2 = new Cs2Stats(Status.OK, profile.skillLevel(), profile.elo(), profile.region(),
                lifetime == null ? null : lifetime.matches(), lifetime == null ? null : lifetime.wins(),
                lifetime == null ? null : lifetime.winRatePercent(), lifetime == null ? null : lifetime.averageKd(),
                lifetime == null ? null : lifetime.averageHeadshotsPercent(),
                lifetime == null ? null : lifetime.currentWinStreak(),
                lifetime == null ? null : lifetime.longestWinStreak(), last,
                detail && lifetime != null ? lifetime.recentResults() : null);
        return Optional.of(new FaceitStats(Status.OK, game, found.nickname(), cs2, null));
    }

    /**
     * The cached or fetched value. A recognised "not found" is cached as a value (a cell with no content, under the
     * source's TTL); any other failure of the fetch is an outage: {@code failed} is set and nothing is cached.
     */
    private <T, W> Looked<T> look(EnrichmentSource source, String key, Class<W> cellType, Function<T, W> wrap,
                                  Function<W, T> unwrap, FetchBudget budget, Supplier<T> fetch) {
        AtomicBoolean failed = new AtomicBoolean();
        Optional<W> cell = cache.get(source, key, cellType, budget, () -> {
            try {
                return Optional.of(wrap.apply(fetch.get()));
            } catch (FaceitNotFoundException e) {
                return Optional.of(wrap.apply(null));
            } catch (RuntimeException e) {
                failed.set(true);
                throw e;
            }
        });
        return new Looked<>(cell.map(unwrap), failed.get());
    }
}
