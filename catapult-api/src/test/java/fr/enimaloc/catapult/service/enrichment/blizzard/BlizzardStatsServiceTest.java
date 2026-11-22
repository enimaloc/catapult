package fr.enimaloc.catapult.service.enrichment.blizzard;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.battlenet.BattleNetFriendLink;
import fr.enimaloc.catapult.domain.blizzard.BlizzardAccount;
import fr.enimaloc.catapult.domain.blizzard.BlizzardWowCharacter;
import fr.enimaloc.catapult.domain.enrichment.GameEnrichmentCacheEntry;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.domain.binding.GameBinding.SourceType;
import fr.enimaloc.catapult.repository.battlenet.BattleNetFriendLinkRepository;
import fr.enimaloc.catapult.repository.blizzard.BlizzardAccountRepository;
import fr.enimaloc.catapult.repository.blizzard.BlizzardWowCharacterRepository;
import fr.enimaloc.catapult.repository.enrichment.GameEnrichmentCacheRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.BlizzardGame;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BlizzardStatsServiceTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final GameEnrichmentCacheRepository repository = mock(GameEnrichmentCacheRepository.class);
    private final BlizzardClient client = mock(BlizzardClient.class);
    private final BlizzardAppTokenService tokens = mock(BlizzardAppTokenService.class);
    private final BattleNetFriendLinkRepository links = mock(BattleNetFriendLinkRepository.class);
    private final BlizzardAccountRepository accounts = mock(BlizzardAccountRepository.class);
    private final BlizzardWowCharacterRepository characters = mock(BlizzardWowCharacterRepository.class);
    private final Map<String, GameEnrichmentCacheEntry> store = new ConcurrentHashMap<>();
    private final EnrichmentProperties properties = new EnrichmentProperties();
    private BlizzardStatsService service;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        when(repository.findBySourceAndLookupKey(anyString(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(store.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        when(repository.save(any(GameEnrichmentCacheEntry.class))).thenAnswer(inv -> {
            GameEnrichmentCacheEntry e = inv.getArgument(0);
            store.put(e.getSource() + "|" + e.getLookupKey(), e);
            return e;
        });
        service = new BlizzardStatsService(new EnrichmentCache(repository, properties, MAPPER), client, tokens,
                properties, links, accounts, characters);
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(tokens.available()).thenReturn(true);
        link(BattleNetFriendLink.Status.ACCEPTED);
        account("eu", "eu", 100L);
        BlizzardWowCharacter main = new BlizzardWowCharacter();
        main.setUserId(user.getId());
        main.setRegion("eu");
        main.setCharacterId(100L);
        main.setRealmSlug("archimonde");
        main.setRealmName("Archimonde");
        main.setName("thrall");
        main.setDisplayName("Thrall");
        main.setLevel(80);
        main.setClassName("Shaman");
        main.setFaction("Horde");
        BlizzardWowCharacter alt = new BlizzardWowCharacter();
        alt.setUserId(user.getId());
        alt.setRegion("eu");
        alt.setCharacterId(101L);
        alt.setRealmSlug("archimonde");
        alt.setRealmName("Archimonde");
        alt.setName("jaina");
        alt.setDisplayName("Jaina");
        alt.setLevel(70);
        alt.setClassName("Mage");
        alt.setFaction("Alliance");
        when(characters.findByUserId(user.getId())).thenReturn(List.of(alt, main));
        when(client.wowCharacter(any(), anyString(), anyString(), anyString())).thenReturn(new WowCharacter(100L,
                "Thrall", "archimonde", "Archimonde", 80, "Shaman", "Elemental", "Orc", "Horde", "Guild", 12000,
                600, 598, Instant.parse("2026-11-20T10:00:00Z"), 2500.5, "http://a"));
        when(client.wowAchievements(any(), anyString(), anyString(), anyString())).thenReturn(new WowAchievements(
                12500, List.of(new WowAchievements.Recent("1", "Ach", Instant.parse("2026-11-19T10:00:00Z")))));
        when(client.sc2Profile(any(), any(), anyString(), anyBoolean())).thenAnswer(inv -> new Sc2Profile(
                "Name" + ((Sc2ProfileRef) inv.getArgument(1)).regionId(), "Clan", "CL", "p", 5, 900,
                Map.of("1v1", new Sc2Profile.Mode(10, "GOLD", 10, 6)),
                (boolean) inv.getArgument(3) ? List.of(new Sc2LadderEntry("1v1", "GOLD", "D", 3, 6, 4)) : List.of()));
        when(client.d3Profile(any(), anyString(), anyString())).thenReturn(new D3Profile("Tag#1", 900, 50, 10, "G",
                100L, 50L, 2L, List.of(new D3Hero(1L, "A", "monk", 70, 100, false, true, false, Instant.EPOCH),
                new D3Hero(2L, "B", "wizard", 70, 200, false, true, false, Instant.EPOCH))));
    }

    private void link(BattleNetFriendLink.Status status) {
        BattleNetFriendLink link = new BattleNetFriendLink();
        link.setBattleTag("Tag#1");
        link.setBnetAccountId(42L);
        link.setStatus(status);
        when(links.findByUserId(user.getId())).thenReturn(Optional.of(link));
    }

    private void account(String region, String mainRegion, Long mainId) {
        BlizzardAccount row = new BlizzardAccount(user.getId());
        row.setRegion(region);
        row.setWowMainRegion(mainRegion);
        row.setWowMainCharacterId(mainId);
        when(accounts.findById(user.getId())).thenReturn(Optional.of(row));
    }

    private Optional<BlizzardStats> stats(BlizzardGame game, boolean detail) {
        return service.forGame(user, game, Locale.FRENCH, FetchBudget.UNLIMITED, detail);
    }

    @Test
    void disabledSourceIsNull() {
        EnrichmentProperties.SourceSettings off = new EnrichmentProperties.SourceSettings();
        off.setEnabled(false);
        properties.setBlizzardD3Profile(off);

        assertThat(stats(BlizzardGame.D3, false)).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void noCredentialsIsNull() {
        when(tokens.available()).thenReturn(false);

        assertThat(stats(BlizzardGame.WOW, false)).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void noBattleNetLinkIsNull() {
        when(links.findByUserId(user.getId())).thenReturn(Optional.empty());

        assertThat(stats(BlizzardGame.D3, false)).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void aRemovedLinkIsNoLink() {
        link(BattleNetFriendLink.Status.REMOVED);

        assertThat(stats(BlizzardGame.SC2, false)).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void fetchFailureIsUnavailableAndNotCached() {
        when(client.d3Profile(any(), anyString(), anyString())).thenThrow(new IllegalStateException("boom"));

        assertThat(stats(BlizzardGame.D3, false).orElseThrow().status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(stats(BlizzardGame.D3, false).orElseThrow().d3().status()).isEqualTo(Status.UNAVAILABLE);

        verify(client, times(2)).d3Profile(any(), anyString(), anyString());
        assertThat(store).isEmpty();
    }

    @Test
    void wowFailureIsUnavailableAndNotCached() {
        when(client.wowCharacter(any(), anyString(), anyString(), anyString()))
                .thenThrow(new BlizzardUnavailableException("down"));

        assertThat(stats(BlizzardGame.WOW, false).orElseThrow().status()).isEqualTo(Status.UNAVAILABLE);
        stats(BlizzardGame.WOW, false);

        verify(client, times(2)).wowCharacter(any(), anyString(), anyString(), anyString());
    }

    @Test
    void achievementsFailureIsUnavailable() {
        when(client.wowAchievements(any(), anyString(), anyString(), anyString()))
                .thenThrow(new BlizzardUnavailableException("down"));

        assertThat(stats(BlizzardGame.WOW, false).orElseThrow().status()).isEqualTo(Status.UNAVAILABLE);
    }

    @Test
    void unknownCharacterIsNotFoundAndCached() {
        when(client.wowCharacter(any(), anyString(), anyString(), anyString()))
                .thenThrow(new BlizzardNotFoundException("no"));

        assertThat(stats(BlizzardGame.WOW, false).orElseThrow().status()).isEqualTo(Status.NOT_FOUND);
        assertThat(stats(BlizzardGame.WOW, false).orElseThrow().wow().status()).isEqualTo(Status.NOT_FOUND);

        verify(client, times(1)).wowCharacter(any(), anyString(), anyString(), anyString());
    }

    @Test
    void wowWithoutImportIsNull() {
        when(accounts.findById(user.getId())).thenReturn(Optional.empty());
        assertThat(stats(BlizzardGame.WOW, false)).isEmpty();

        BlizzardAccount noMain = new BlizzardAccount(user.getId());
        noMain.setRegion("eu");
        when(accounts.findById(user.getId())).thenReturn(Optional.of(noMain));
        assertThat(stats(BlizzardGame.WOW, false)).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void wowOkProjection() {
        BlizzardStats s = stats(BlizzardGame.WOW, false).orElseThrow();

        assertThat(s.status()).isEqualTo(Status.OK);
        assertThat(s.game()).isEqualTo(BlizzardGame.WOW);
        assertThat(s.account()).isEqualTo("Tag#1");
        assertThat(s.region()).isEqualTo("eu");
        assertThat(s.wow().name()).isEqualTo("Thrall");
        assertThat(s.wow().achievementPoints()).isEqualTo(12500);
        assertThat(s.wow().recentAchievements()).hasSize(1);
        assertThat(s.sc2()).isNull();
        assertThat(s.d3()).isNull();
    }

    @Test
    void sc2PicksTheMembersRegion() {
        when(client.sc2Profiles(42L)).thenReturn(List.of(new Sc2ProfileRef(1, 1, 11L, "us"),
                new Sc2ProfileRef(2, 1, 22L, "eu")));

        BlizzardStats s = stats(BlizzardGame.SC2, false).orElseThrow();

        assertThat(s.sc2().name()).isEqualTo("Name2");
        assertThat(s.status()).isEqualTo(Status.OK);
    }

    @Test
    void sc2WithoutMatchingRegionTakesTheFirst() {
        when(client.sc2Profiles(42L)).thenReturn(List.of(new Sc2ProfileRef(1, 1, 11L, "us")));

        assertThat(stats(BlizzardGame.SC2, false).orElseThrow().sc2().name()).isEqualTo("Name1");
    }

    @Test
    void sc2WithoutProfileIsNotFound() {
        when(client.sc2Profiles(42L)).thenReturn(List.of());

        assertThat(stats(BlizzardGame.SC2, false).orElseThrow().status()).isEqualTo(Status.NOT_FOUND);
        verify(client, never()).sc2Profile(any(), any(), anyString(), anyBoolean());
    }

    @Test
    void sc2ProfilesFailureIsUnavailable() {
        when(client.sc2Profiles(anyLong())).thenThrow(new BlizzardUnavailableException("down"));

        assertThat(stats(BlizzardGame.SC2, false).orElseThrow().status()).isEqualTo(Status.UNAVAILABLE);
    }

    @Test
    void d3LastHeroIsTheLastPlayed() {
        BlizzardStats s = stats(BlizzardGame.D3, false).orElseThrow();

        assertThat(s.d3().lastHero().name()).isEqualTo("B");
        assertThat(s.d3().battleTag()).isEqualTo("Tag#1");
    }

    @Test
    void d3NotFound() {
        when(client.d3Profile(any(), anyString(), anyString())).thenThrow(new BlizzardNotFoundException("no"));

        assertThat(stats(BlizzardGame.D3, false).orElseThrow().status()).isEqualTo(Status.NOT_FOUND);
    }

    @Test
    void detailFieldsOnlyInDetail() {
        when(client.sc2Profiles(42L)).thenReturn(List.of(new Sc2ProfileRef(2, 1, 22L, "eu")));

        BlizzardStats wowLight = stats(BlizzardGame.WOW, false).orElseThrow();
        BlizzardStats sc2Light = stats(BlizzardGame.SC2, false).orElseThrow();
        BlizzardStats d3Light = stats(BlizzardGame.D3, false).orElseThrow();
        assertThat(wowLight.wow().characters()).isNull();
        assertThat(sc2Light.sc2().ladder()).isNull();
        assertThat(d3Light.d3().heroes()).isNull();

        BlizzardStats wow = stats(BlizzardGame.WOW, true).orElseThrow();
        assertThat(wow.wow().characters()).extracting(BlizzardStats.WowStats.Character::name)
                .containsExactly("Thrall", "Jaina");
        assertThat(wow.wow().characters().get(0).main()).isTrue();
        assertThat(stats(BlizzardGame.SC2, true).orElseThrow().sc2().ladder()).hasSize(1);
        assertThat(stats(BlizzardGame.D3, true).orElseThrow().d3().heroes()).hasSize(2);
    }

    @Test
    void personalKeysCarryTheMember() {
        when(client.sc2Profiles(42L)).thenReturn(List.of(new Sc2ProfileRef(2, 1, 22L, "eu")));

        stats(BlizzardGame.WOW, false);
        stats(BlizzardGame.SC2, false);
        stats(BlizzardGame.D3, false);

        assertThat(store).isNotEmpty();
        assertThat(store.values()).allSatisfy(e -> assertThat(e.getLookupKey())
                .startsWith("u:" + user.getId() + ":"));
        assertThat(store.values()).extracting(GameEnrichmentCacheEntry::getLookupKey)
                .contains("u:" + user.getId() + ":wow:eu:100");
    }

    @Test
    void variantIsUnsupported() {
        DetectedGame variant = new DetectedGame("WoW:Forever", SourceType.BATTLENET, "WoW");

        BlizzardStats s = service.forDetected(user, variant, null, Locale.FRENCH, FetchBudget.UNLIMITED)
                .orElseThrow();

        assertThat(s.status()).isEqualTo(Status.UNSUPPORTED);
        assertThat(s.game()).isEqualTo(BlizzardGame.WOW);
        verifyNoInteractions(client);
    }

    @Test
    void variantWithoutLinkIsNull() {
        when(links.findByUserId(user.getId())).thenReturn(Optional.empty());

        assertThat(service.forDetected(user, new DetectedGame("WoW:Forever", SourceType.BATTLENET, "WoW"), null,
                Locale.FRENCH, FetchBudget.UNLIMITED)).isEmpty();
    }

    @Test
    void detectedGameResolvesAndUnknownIsEmpty() {
        assertThat(service.forDetected(user, new DetectedGame("D3", SourceType.BATTLENET, "D3"), null,
                Locale.FRENCH, FetchBudget.UNLIMITED).orElseThrow().game()).isEqualTo(BlizzardGame.D3);
        assertThat(service.forDetected(user, new DetectedGame("440", SourceType.STEAM, "TF2"), "999",
                Locale.FRENCH, FetchBudget.UNLIMITED)).isEmpty();
    }

    @Test
    void hearthstoneHasNoPersonalStats() {
        assertThat(stats(BlizzardGame.HEARTHSTONE, false)).isEmpty();
        verify(client, never()).wowCharacter(any(), anyString(), anyString(), eq("fr_FR"));
        verifyNoInteractions(client);
    }
}
