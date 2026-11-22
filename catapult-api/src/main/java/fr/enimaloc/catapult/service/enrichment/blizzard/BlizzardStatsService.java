package fr.enimaloc.catapult.service.enrichment.blizzard;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.battlenet.BattleNetFriendLink;
import fr.enimaloc.catapult.domain.blizzard.BlizzardAccount;
import fr.enimaloc.catapult.domain.blizzard.BlizzardWowCharacter;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.battlenet.BattleNetFriendLinkRepository;
import fr.enimaloc.catapult.repository.blizzard.BlizzardAccountRepository;
import fr.enimaloc.catapult.repository.blizzard.BlizzardWowCharacterRepository;
import fr.enimaloc.catapult.service.blizzard.BlizzardAccountService;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardStats.D3Stats;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardStats.Sc2Stats;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardStats.WowStats;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.BlizzardGame;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Achievement;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A member's personal stats on a Blizzard game (World of Warcraft main character, StarCraft II profile, Diablo III
 * profile), through the shared cache under {@code u:<uuid>:...} keys. An absence Blizzard confirmed is cached
 * ({@code not_found}); an outage (any failure of the fetch) is never cached and says {@code unavailable}. A source
 * that is off, a missing application credential, Battle.net link or WoW import give no object at all.
 */
@Service
public class BlizzardStatsService {

    /** The cached shape of the StarCraft II profiles of an account. */
    public record Sc2Refs(List<Sc2ProfileRef> refs) {
        public Sc2Refs {
            refs = List.copyOf(refs);
        }
    }

    /**
     * The cached shapes. A value that is null is a recognised "not found", cached as a value (so under the
     * source's own TTL, not as a known absence).
     */
    public record CharacterCell(WowCharacter value) {
    }

    public record AchievementsCell(WowAchievements value) {
    }

    public record Sc2ProfileCell(Sc2Profile value) {
    }

    public record D3ProfileCell(D3Profile value) {
    }

    /** A cached value, or the fact that the upstream call failed (an outage, as opposed to "nothing there"). */
    private record Looked<T>(Optional<T> value, boolean failed) {
    }

    private final EnrichmentCache cache;
    private final BlizzardClient client;
    private final BlizzardAppTokenService tokens;
    private final EnrichmentProperties properties;
    private final BattleNetFriendLinkRepository links;
    private final BlizzardAccountRepository accounts;
    private final BlizzardWowCharacterRepository characters;
    private final boolean battleNetOn;

    @Autowired
    public BlizzardStatsService(EnrichmentCache cache, BlizzardClient client, BlizzardAppTokenService tokens,
                                EnrichmentProperties properties, BattleNetFriendLinkRepository links,
                                BlizzardAccountRepository accounts, BlizzardWowCharacterRepository characters,
                                Optional<BlizzardAccountService> battleNet) {
        this.cache = cache;
        this.client = client;
        this.tokens = tokens;
        this.properties = properties;
        this.links = links;
        this.accounts = accounts;
        this.characters = characters;
        this.battleNetOn = battleNet.isPresent();
    }

    /**
     * The member's stats on {@code game}; empty when there is nothing to say (no credentials, no Battle.net link,
     * the source is off, Hearthstone, WoW not imported). {@code detail} also fills the characters, ladder or heroes.
     *
     * @throws fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException when {@code budget} refuses
     *         a call that is needed
     */
    public Optional<BlizzardStats> forGame(UserAccount user, BlizzardGame game, Locale locale, FetchBudget budget,
                                           boolean detail) {
        if (game == BlizzardGame.HEARTHSTONE) {
            return Optional.empty();
        }
        Optional<BattleNetFriendLink> link = linkIfServable(user, profileSource(game));
        if (link.isEmpty()) {
            return Optional.empty();
        }
        Optional<BlizzardAccount> row = accounts.findById(user.getId());
        BlizzardRegion region = regionOf(row);
        String battleTag = link.get().getBattleTag();
        String member = "u:" + user.getId();
        return switch (game) {
            case WOW -> wow(user, row, region, battleTag, member, locale, budget, detail);
            case SC2 -> Optional.of(of(game, battleTag, region, null,
                    sc2(link.get().getBnetAccountId(), region, member, locale, budget, detail), null));
            case D3 -> Optional.of(of(game, battleTag, region, null, null,
                    d3(region, battleTag, member, locale, budget, detail)));
            case HEARTHSTONE -> Optional.empty();
        };
    }

    /**
     * The member's stats for the game being played. A Battle.net variant (WoW Classic, {@code WoW:Forever}) is not
     * the retail game the stats are about: {@code unsupported}, when the member has a Battle.net link.
     */
    public Optional<BlizzardStats> forDetected(UserAccount user, DetectedGame detected, String igdbId, Locale locale,
                                               FetchBudget budget) {
        Optional<BlizzardGame> game = PublisherGames.blizzard(detected, igdbId);
        if (game.isPresent()) {
            return forGame(user, game.get(), locale, budget, false);
        }
        if (PublisherGames.isBlizzardVariant(detected)) {
            return linkIfServable(user, EnrichmentSource.BLIZZARD_WOW_CHARACTER).map(link -> new BlizzardStats(
                    Status.UNSUPPORTED, BlizzardGame.WOW, link.getBattleTag(), region(user).key(), null, null, null,
                    null));
        }
        return Optional.empty();
    }

    /** The member's Blizzard region (the one they chose or their first WoW import set), else the default one. */
    public BlizzardRegion region(UserAccount user) {
        return regionOf(accounts.findById(user.getId()));
    }

    private BlizzardRegion regionOf(Optional<BlizzardAccount> row) {
        return row.flatMap(r -> BlizzardRegion.parse(r.getRegion())).orElse(properties.blizzardDefaultRegion());
    }

    private Optional<BlizzardStats> wow(UserAccount user, Optional<BlizzardAccount> row, BlizzardRegion region,
                                        String battleTag, String member, Locale locale, FetchBudget budget,
                                        boolean detail) {
        if (row.isEmpty() || row.get().getWowMainRegion() == null || row.get().getWowMainCharacterId() == null) {
            return Optional.empty();
        }
        List<BlizzardWowCharacter> imported = characters.findByUserId(user.getId());
        String mainRegion = row.get().getWowMainRegion();
        long mainId = row.get().getWowMainCharacterId();
        Optional<BlizzardWowCharacter> main = imported.stream()
                .filter(c -> c.getRegion().equals(mainRegion) && c.getCharacterId() == mainId).findFirst();
        Optional<BlizzardRegion> mainHost = BlizzardRegion.parse(mainRegion);
        if (main.isEmpty() || mainHost.isEmpty()) {
            return Optional.empty();
        }
        BlizzardWowCharacter pick = main.get();
        BlizzardRegion host = mainHost.get();
        String blizzardLocale = BlizzardLocale.of(locale, host);
        String key = member + ":wow:" + mainRegion + ":" + mainId;
        List<WowStats.Character> list = detail ? characterList(imported, mainRegion, mainId) : null;

        Looked<WowCharacter> profile = look(EnrichmentSource.BLIZZARD_WOW_CHARACTER, key, CharacterCell.class,
                CharacterCell::new, CharacterCell::value, budget, () -> client.wowCharacter(host, pick.getRealmSlug(), pick.getName(), blizzardLocale));
        if (profile.value().isEmpty()) {
            return Optional.of(of(BlizzardGame.WOW, battleTag, region,
                    wowStatus(profile.failed() ? Status.UNAVAILABLE : Status.NOT_FOUND, list), null, null));
        }
        WowCharacter character = profile.value().get();
        WowAchievements achievements = null;
        if (properties.source(EnrichmentSource.BLIZZARD_WOW_ACHIEVEMENTS).enabled()) {
            // The achievements are a part: without them the character is still served
            Looked<WowAchievements> got = look(EnrichmentSource.BLIZZARD_WOW_ACHIEVEMENTS, key,
                    AchievementsCell.class, AchievementsCell::new, AchievementsCell::value, budget,
                    () -> client.wowAchievements(host, pick.getRealmSlug(), pick.getName(), blizzardLocale));
            achievements = got.value().orElse(null);
        }
        List<Achievement> recent = achievements == null ? null : achievements.recent().stream()
                .map(a -> new Achievement(a.id(), a.name(), null, null, true, a.unlockedAt(), null, null, false))
                .toList();
        WowStats stats = new WowStats(Status.OK, character.name(), character.realmName(), character.level(),
                character.className(), character.specName(), character.raceName(), character.faction(),
                character.guild(), achievements == null ? character.achievementPoints() : achievements.points(),
                character.averageItemLevel(), character.equippedItemLevel(), character.lastLogin(),
                character.mythicRating(), character.avatarUrl(), recent, list);
        return Optional.of(of(BlizzardGame.WOW, battleTag, region, stats, null, null));
    }

    private static WowStats wowStatus(Status status, List<WowStats.Character> list) {
        return new WowStats(status, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, list);
    }

    private static List<WowStats.Character> characterList(List<BlizzardWowCharacter> imported, String mainRegion,
                                                           long mainId) {
        return imported.stream()
                .sorted(Comparator.comparing(BlizzardWowCharacter::getLevel).reversed()
                        .thenComparing(BlizzardWowCharacter::getName))
                .map(c -> new WowStats.Character(c.getRegion(), c.getDisplayName(), c.getRealmName(), c.getLevel(),
                        c.getClassName(), c.getFaction(),
                        c.getRegion().equals(mainRegion) && c.getCharacterId() == mainId))
                .toList();
    }

    private Sc2Stats sc2(long bnetAccountId, BlizzardRegion region, String member, Locale locale,
                         FetchBudget budget, boolean detail) {
        Looked<Sc2Refs> refs = look(EnrichmentSource.BLIZZARD_SC2_PROFILE, member + ":sc2:profiles", Sc2Refs.class,
                r -> r == null ? new Sc2Refs(List.of()) : r, r -> r, budget, () -> new Sc2Refs(client.sc2Profiles(bnetAccountId)));
        if (refs.value().isEmpty() && refs.failed()) {
            return sc2Status(Status.UNAVAILABLE);
        }
        List<Sc2ProfileRef> all = refs.value().map(Sc2Refs::refs).orElse(List.of());
        if (all.isEmpty()) {
            return sc2Status(Status.NOT_FOUND);
        }
        Sc2ProfileRef ref = all.stream().filter(r -> r.regionId() == region.sc2RegionId()).findFirst()
                .orElse(all.get(0));
        String key = member + ":sc2:" + ref.regionId() + ":" + ref.realmId() + ":" + ref.profileId()
                + (detail ? ":ladder" : "");
        BlizzardRegion host = hostOf(ref, region);
        String blizzardLocale = BlizzardLocale.of(locale, host);
        Looked<Sc2Profile> profile = look(EnrichmentSource.BLIZZARD_SC2_PROFILE, key, Sc2ProfileCell.class,
                Sc2ProfileCell::new, Sc2ProfileCell::value, budget,
                () -> client.sc2Profile(host, ref, blizzardLocale, detail));
        if (profile.value().isEmpty()) {
            return sc2Status(profile.failed() ? Status.UNAVAILABLE : Status.NOT_FOUND);
        }
        Sc2Profile p = profile.value().get();
        return new Sc2Stats(Status.OK, p.displayName(), p.clanName(), p.clanTag(), p.portrait(), p.swarmLevel(),
                p.achievementPoints(), p.modes(), detail ? p.ladder() : null);
    }

    /** The host of a profile: the member's region when it is theirs, else the region the profile belongs to. */
    private static BlizzardRegion hostOf(Sc2ProfileRef ref, BlizzardRegion region) {
        if (ref.regionId() == region.sc2RegionId()) {
            return region;
        }
        return switch (ref.regionId()) {
            case 1 -> BlizzardRegion.US;
            case 2 -> BlizzardRegion.EU;
            case 3 -> BlizzardRegion.KR;
            default -> region;
        };
    }

    private static Sc2Stats sc2Status(Status status) {
        return new Sc2Stats(status, null, null, null, null, null, null, null, null);
    }

    private D3Stats d3(BlizzardRegion region, String battleTag, String member, Locale locale, FetchBudget budget,
                       boolean detail) {
        String blizzardLocale = BlizzardLocale.of(locale, region);
        Looked<D3Profile> profile = look(EnrichmentSource.BLIZZARD_D3_PROFILE,
                member + ":d3:" + region.key() + ":" + battleTag, D3ProfileCell.class,
                D3ProfileCell::new, D3ProfileCell::value, budget,
                () -> client.d3Profile(region, battleTag, blizzardLocale));
        if (profile.value().isEmpty()) {
            return d3Status(profile.failed() ? Status.UNAVAILABLE : Status.NOT_FOUND);
        }
        D3Profile p = profile.value().get();
        D3Hero last = p.lastHeroPlayed() == null ? null : p.heroes().stream()
                .filter(h -> h.id() == p.lastHeroPlayed()).findFirst().orElse(null);
        return new D3Stats(Status.OK, p.battleTag(), p.paragonLevel(), p.paragonLevelSeason(),
                p.paragonLevelHardcore(), p.guildName(), p.monstersKilled(), p.elitesKilled(), last,
                detail ? new ArrayList<>(p.heroes()) : null);
    }

    private static D3Stats d3Status(Status status) {
        return new D3Stats(status, null, null, null, null, null, null, null, null, null);
    }

    private static BlizzardStats of(BlizzardGame game, String battleTag, BlizzardRegion region, WowStats wow,
                                    Sc2Stats sc2, D3Stats d3) {
        Status status = wow != null ? wow.status() : sc2 != null ? sc2.status() : d3.status();
        return new BlizzardStats(status, game, battleTag, region.key(), wow, sc2, d3, null);
    }

    private static EnrichmentSource profileSource(BlizzardGame game) {
        return switch (game) {
            case WOW, HEARTHSTONE -> EnrichmentSource.BLIZZARD_WOW_CHARACTER;
            case SC2 -> EnrichmentSource.BLIZZARD_SC2_PROFILE;
            case D3 -> EnrichmentSource.BLIZZARD_D3_PROFILE;
        };
    }

    /** The member's active link, when Battle.net is on, the application can ask and {@code source} is enabled. */
    private Optional<BattleNetFriendLink> linkIfServable(UserAccount user, EnrichmentSource source) {
        if (!battleNetOn || !tokens.available() || !properties.source(source).enabled()) {
            return Optional.empty();
        }
        return activeLink(user);
    }

    private Optional<BattleNetFriendLink> activeLink(UserAccount user) {
        return links.findByUserId(user.getId()).filter(l -> l.getStatus() != BattleNetFriendLink.Status.REMOVED);
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
            } catch (BlizzardNotFoundException e) {
                return Optional.of(wrap.apply(null));
            } catch (RuntimeException e) {
                failed.set(true);
                throw e;
            }
        });
        return new Looked<>(cell.map(unwrap), failed.get());
    }
}
