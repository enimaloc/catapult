package fr.enimaloc.catapult.service.enrichment.hypixel;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.hypixel.HypixelAccount;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.getter.hypixel.HypixelApiKeyRotator;
import fr.enimaloc.catapult.repository.hypixel.HypixelAccountRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.BedWarsRaw;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.HypixelGames;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.HypixelPlayer;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.HypixelSession;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.SkyWarsRaw;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelStats.HypixelBedWarsStats;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelStats.HypixelNetworkStats;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelStats.HypixelOnlineStatus;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelStats.HypixelSkyWarsStats;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.HypixelGame;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A member's personal stats on Hypixel (network level, karma, achievement points, rank, Bed Wars and SkyWars stars
 * and wins, where they are now), through the shared cache under {@code u:<uuid>:...} keys, by the Minecraft UUID
 * they declared. A UUID that never joined Hypixel is cached ({@code not_found}); an outage (any failure of the
 * fetch) is never cached and says {@code unavailable}. The online status is {@code private} when the player hides it
 * (no call is made then). The source off (disabled, or no key in the pool) or no declared account gives no object.
 */
@Service
public class HypixelStatsService {

    /** A player, or null for a UUID Hypixel does not know (cached as a value, under the source's TTL). */
    public record PlayerCell(HypixelPlayer value) {
    }

    /** A cached value, or the fact that the upstream call failed (an outage, as opposed to "nothing there"). */
    private record Looked<T>(Optional<T> value, boolean failed) {
    }

    private final EnrichmentCache cache;
    private final HypixelClient client;
    private final HypixelApiKeyRotator keys;
    private final HypixelAccountRepository accounts;
    private final HypixelGameDataService gameData;
    private final EnrichmentProperties properties;

    public HypixelStatsService(EnrichmentCache cache, HypixelClient client, HypixelApiKeyRotator keys,
                               HypixelAccountRepository accounts, HypixelGameDataService gameData,
                               EnrichmentProperties properties) {
        this.cache = cache;
        this.client = client;
        this.keys = keys;
        this.accounts = accounts;
        this.gameData = gameData;
        this.properties = properties;
    }

    /** Whether {@code detected} is the game Hypixel is played on (Minecraft). */
    public Optional<HypixelGame> gameOf(DetectedGame detected, String igdbId) {
        return PublisherGames.hypixel(detected, igdbId, properties.getHypixel().getIgdbIds());
    }

    /** The member's stats when the game being played is Minecraft; empty otherwise. */
    public Optional<HypixelStats> forDetected(UserAccount user, DetectedGame detected, String igdbId,
                                              FetchBudget budget) {
        return gameOf(detected, igdbId).flatMap(game -> forMember(user, budget, false));
    }

    /**
     * The member's stats; empty when there is nothing to say (the source is off, no key in the pool, no Minecraft
     * account declared). {@code detail} also fills the Bed Wars and SkyWars details.
     *
     * @throws fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException when {@code budget} refuses
     *         a call that is needed
     */
    public Optional<HypixelStats> forMember(UserAccount user, FetchBudget budget, boolean detail) {
        if (!properties.hypixelEnabled() || !keys.hasKeys()
                || !properties.source(EnrichmentSource.HYPIXEL_PLAYER).enabled()) {
            return Optional.empty();
        }
        Optional<HypixelAccount> row = accounts.findById(user.getId());
        if (row.isEmpty()) {
            return Optional.empty();
        }
        HypixelAccount account = row.get();
        String uuid = account.getMinecraftUuid();
        String key = "u:" + user.getId() + ":" + uuid;
        Looked<HypixelPlayer> player = look(EnrichmentSource.HYPIXEL_PLAYER, key, PlayerCell.class, PlayerCell::new,
                PlayerCell::value, budget, () -> client.player(uuid));
        if (player.value().isEmpty()) {
            Status status = player.failed() ? Status.UNAVAILABLE : Status.NOT_FOUND;
            return Optional.of(new HypixelStats(status, HypixelGame.HYPIXEL, account.getMinecraftName(), null, null,
                    null, null, null));
        }
        HypixelPlayer p = player.value().get();
        String name = p.displayName() == null ? account.getMinecraftName() : p.displayName();
        return Optional.of(new HypixelStats(Status.OK, HypixelGame.HYPIXEL, name,
                new HypixelNetworkStats(networkLevel(p.networkExp()), p.networkExp(), p.karma(),
                        p.achievementPoints(), p.rank(), p.lastLogin()),
                bedWars(p.bedWars(), detail), skyWars(p.skyWars(), detail), online(p, key, budget), null));
    }

    /** The online status: {@code private} when hidden (nothing asked), null when that source is off. */
    private HypixelOnlineStatus online(HypixelPlayer player, String key, FetchBudget budget) {
        if (player.onlineStatusHidden()) {
            return new HypixelOnlineStatus(Status.PRIVATE, null, null, null, null, null);
        }
        if (!properties.source(EnrichmentSource.HYPIXEL_STATUS).enabled()) {
            return null;
        }
        Looked<HypixelSession> session = look(EnrichmentSource.HYPIXEL_STATUS, key, HypixelSession.class, s -> s,
                s -> s, budget, () -> client.status(player.uuid()));
        if (session.value().isEmpty()) {
            return new HypixelOnlineStatus(Status.UNAVAILABLE, null, null, null, null, null);
        }
        HypixelSession s = session.value().get();
        String gameName = s.gameType() == null ? null
                : gameData.games(budget).map(HypixelGames::names).map(n -> n.get(s.gameType())).orElse(null);
        return new HypixelOnlineStatus(Status.OK, s.online(), s.gameType(), gameName, s.mode(), s.map());
    }

    private static HypixelBedWarsStats bedWars(BedWarsRaw raw, boolean detail) {
        if (raw == null) {
            return null;
        }
        return detail
                ? new HypixelBedWarsStats(raw.stars(), raw.wins(), raw.losses(), raw.finalKills(), raw.finalDeaths(),
                        raw.bedsBroken())
                : new HypixelBedWarsStats(raw.stars(), raw.wins(), null, null, null, null);
    }

    private static HypixelSkyWarsStats skyWars(SkyWarsRaw raw, boolean detail) {
        if (raw == null) {
            return null;
        }
        return detail
                ? new HypixelSkyWarsStats(raw.stars(), raw.wins(), raw.losses(), raw.kills(), raw.deaths())
                : new HypixelSkyWarsStats(raw.stars(), raw.wins(), null, null, null);
    }

    /**
     * The network level Hypixel shows for {@code experience}: 1 at 0, each level costing 2 500 more than the one
     * before, from 10 000 ({@code 1 + (-3.5 + sqrt(12.25 + 0.0008 * xp))}, floored).
     */
    static int networkLevel(long experience) {
        if (experience <= 0) {
            return 1;
        }
        return (int) Math.floor(1 - 3.5 + Math.sqrt(12.25 + 0.0008 * experience));
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
                return Optional.ofNullable(wrap.apply(fetch.get()));
            } catch (HypixelNotFoundException e) {
                return Optional.ofNullable(wrap.apply(null));
            } catch (RuntimeException e) {
                failed.set(true);
                throw e;
            }
        });
        return new Looked<>(cell.map(unwrap), failed.get());
    }
}
