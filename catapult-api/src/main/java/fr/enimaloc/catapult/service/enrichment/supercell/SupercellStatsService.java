package fr.enimaloc.catapult.service.enrichment.supercell;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.supercell.SupercellAccount;
import fr.enimaloc.catapult.getter.supercell.SupercellApiKeyRotator;
import fr.enimaloc.catapult.repository.supercell.SupercellAccountRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.EnrichmentUnavailableException;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellModels.BsPlayer;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellModels.CocPlayer;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellModels.CrPlayer;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellModels.Group;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellModels.SupercellPlayer;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellStats.BrawlStarsStats;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellStats.Brawler;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellStats.Card;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellStats.Clan;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellStats.ClashOfClansStats;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellStats.ClashRoyaleStats;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellStats.Hero;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A member's personal stats on a Supercell game (Clash of Clans: town hall, trophies, league…; Clash Royale:
 * trophies, arena, wins…; Brawl Stars: trophies, victories…), through the shared cache under
 * {@code u:<uuid>:<tag>} keys, by the player tag they declared for that game. A tag no player has any more is cached
 * ({@code not_found}); an outage (any failure of the fetch) is never cached and says {@code unavailable}, and that
 * member's tag is not asked again at once (the cache's backoff), so that nobody can spend a game's key pool by
 * repeating a failing call. The source off (disabled, or no key of that game in the pool) or no tag declared for the
 * game gives no object at all.
 */
@Service
public class SupercellStatsService {

    /** A player, or null for a tag Supercell does not know (cached as a value, under the source's TTL). */
    public record PlayerCell(SupercellPlayer value) {
    }

    private record Looked(Optional<SupercellPlayer> value, boolean failed) {
    }

    private final EnrichmentCache cache;
    private final SupercellClient client;
    private final SupercellApiKeyRotator keys;
    private final SupercellAccountRepository accounts;
    private final EnrichmentProperties properties;

    public SupercellStatsService(EnrichmentCache cache, SupercellClient client, SupercellApiKeyRotator keys,
                                 SupercellAccountRepository accounts, EnrichmentProperties properties) {
        this.cache = cache;
        this.client = client;
        this.keys = keys;
        this.accounts = accounts;
        this.properties = properties;
    }

    /** The cache source of {@code game}'s players. */
    public static EnrichmentSource source(SupercellGame game) {
        return switch (game) {
            case CLASH_OF_CLANS -> EnrichmentSource.SUPERCELL_CLASH_OF_CLANS;
            case CLASH_ROYALE -> EnrichmentSource.SUPERCELL_CLASH_ROYALE;
            case BRAWL_STARS -> EnrichmentSource.SUPERCELL_BRAWL_STARS;
        };
    }

    /** The cache key of a member's player: their id and the tag (so another tag is another entry). */
    public static String cacheKey(UUID userId, String tag) {
        return "u:" + userId + ":" + tag;
    }

    /** Whether the Supercell source can serve {@code game} on this instance (enabled, with a key of the game). */
    public boolean available(SupercellGame game) {
        return properties.supercellEnabled() && keys.hasKeys(game) && properties.source(source(game)).enabled();
    }

    /**
     * The Supercell game being played: by the configured IGDB ids, else by its name ({@code Clash of Clans},
     * {@code Clash Royale}, {@code Brawl Stars}, as Twitch and IGDB name them).
     */
    public Optional<SupercellGame> gameOf(String igdbId, String name) {
        EnrichmentProperties.Supercell supercell = properties.getSupercell();
        if (igdbId != null && !igdbId.isBlank()) {
            if (igdbId.equals(supercell.getClashOfClansIgdbId())) {
                return Optional.of(SupercellGame.CLASH_OF_CLANS);
            }
            if (igdbId.equals(supercell.getClashRoyaleIgdbId())) {
                return Optional.of(SupercellGame.CLASH_ROYALE);
            }
            if (igdbId.equals(supercell.getBrawlStarsIgdbId())) {
                return Optional.of(SupercellGame.BRAWL_STARS);
            }
        }
        return SupercellGame.byTitle(name);
    }

    /** The member's stats on the game being played; empty when it is no Supercell game. */
    public Optional<SupercellStats> forDetected(UserAccount user, String igdbId, String name, FetchBudget budget) {
        return gameOf(igdbId, name).flatMap(game -> forGame(user, game, budget, false));
    }

    /**
     * The member's stats on {@code game}; empty when there is nothing to say (the source is off, no key of the game
     * in the pool, no tag declared for it). {@code detail} also fills what only the detail route has.
     *
     * @throws fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException when {@code budget} refuses
     *         a call that is needed
     */
    public Optional<SupercellStats> forGame(UserAccount user, SupercellGame game, FetchBudget budget,
                                            boolean detail) {
        if (!available(game)) {
            return Optional.empty();
        }
        Optional<SupercellAccount> row = accounts.findById(user.getId());
        String tag = row.map(r -> r.tag(game)).orElse(null);
        if (tag == null) {
            return Optional.empty();
        }
        String declaredName = row.get().name(game);
        Looked player = look(game, cacheKey(user.getId(), tag), budget, () -> client.player(game, tag));
        if (player.value().isEmpty()) {
            Status status = player.failed() ? Status.UNAVAILABLE : Status.NOT_FOUND;
            return Optional.of(new SupercellStats(status, game, declaredName, tag, null, null, null, null));
        }
        SupercellPlayer p = player.value().get();
        return Optional.of(new SupercellStats(Status.OK, game, p.name(), tag,
                p.clashOfClans() == null ? null : clashOfClans(p.clashOfClans(), detail),
                p.clashRoyale() == null ? null : clashRoyale(p.clashRoyale(), detail),
                p.brawlStars() == null ? null : brawlStars(p.brawlStars(), detail), null));
    }

    private static ClashOfClansStats clashOfClans(CocPlayer c, boolean detail) {
        return new ClashOfClansStats(c.townHallLevel(), c.townHallWeaponLevel(), c.expLevel(), c.trophies(),
                c.bestTrophies(), c.league(), c.leagueIconUrl(), c.warStars(), c.builderHallLevel(),
                c.builderBaseTrophies(), clan(c.clan()), detail ? c.attackWins() : null,
                detail ? c.defenseWins() : null,
                detail ? c.heroes().stream().map(h -> new Hero(h.name(), h.level(), h.maxLevel(), h.village()))
                        .toList() : null);
    }

    private static ClashRoyaleStats clashRoyale(CrPlayer c, boolean detail) {
        return new ClashRoyaleStats(c.expLevel(), c.trophies(), c.bestTrophies(), c.arena(), c.pathOfLegendLeague(),
                c.wins(), c.losses(), c.threeCrownWins(), clan(c.clan()), detail ? c.battleCount() : null,
                detail ? c.currentDeck().stream().map(d -> new Card(d.name(), d.level(), d.maxLevel())).toList()
                        : null);
    }

    private static BrawlStarsStats brawlStars(BsPlayer b, boolean detail) {
        return new BrawlStarsStats(b.expLevel(), b.trophies(), b.highestTrophies(), b.trioVictories(),
                b.soloVictories(), b.duoVictories(), clan(b.club()),
                detail ? b.brawlers().stream().map(x -> new Brawler(x.name(), x.power(), x.rank(), x.trophies(),
                        x.highestTrophies())).toList() : null);
    }

    private static Clan clan(Group group) {
        return group == null ? null : new Clan(group.tag(), group.name());
    }

    /**
     * The cached or fetched player. A recognised "not found" is cached as a value (a cell with no content, under the
     * source's TTL); any other failure of the fetch is an outage: {@code failed} is set and nothing is cached. A
     * fetch left alone by the cache's backoff after a recent failure is an outage too.
     */
    private Looked look(SupercellGame game, String key, FetchBudget budget,
                        java.util.function.Supplier<SupercellPlayer> fetch) {
        AtomicBoolean failed = new AtomicBoolean();
        Optional<PlayerCell> cell;
        try {
            cell = cache.get(source(game), key, PlayerCell.class, budget, () -> {
                try {
                    return Optional.of(new PlayerCell(fetch.get()));
                } catch (SupercellNotFoundException e) {
                    return Optional.of(new PlayerCell(null));
                } catch (RuntimeException e) {
                    failed.set(true);
                    throw e;
                }
            });
        } catch (EnrichmentUnavailableException e) {
            return new Looked(Optional.empty(), true);
        }
        return new Looked(cell.map(PlayerCell::value), failed.get());
    }
}
