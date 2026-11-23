package fr.enimaloc.catapult.service.enrichment.hypixel;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.enrichment.GameEnrichmentCacheEntry;
import fr.enimaloc.catapult.domain.hypixel.HypixelAccount;
import fr.enimaloc.catapult.domain.hypixel.HypixelApiKeyEntry;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.getter.hypixel.HypixelApiKeyRotator;
import fr.enimaloc.catapult.repository.enrichment.GameEnrichmentCacheRepository;
import fr.enimaloc.catapult.repository.hypixel.HypixelAccountRepository;
import fr.enimaloc.catapult.repository.hypixel.HypixelApiKeyRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.BedWarsRaw;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.HypixelGameInfo;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.HypixelGames;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.HypixelPlayer;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.HypixelSession;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.SkyWarsRaw;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.HypixelGame;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HypixelStatsServiceTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String MC = "069a79f444e94726a5befca90e38aaf5";

    private final GameEnrichmentCacheRepository repository = mock(GameEnrichmentCacheRepository.class);
    private final Map<String, GameEnrichmentCacheEntry> store = new ConcurrentHashMap<>();
    private final HypixelClient client = mock(HypixelClient.class);
    private final HypixelApiKeyRepository keyRepository = mock(HypixelApiKeyRepository.class);
    private final HypixelAccountRepository accounts = mock(HypixelAccountRepository.class);
    private final EnrichmentProperties properties = new EnrichmentProperties();
    private HypixelApiKeyRotator keys;
    private HypixelStatsService service;
    private HypixelGameDataService gameData;
    private UserAccount user;

    static HypixelPlayer player(boolean hidden) {
        return new HypixelPlayer(MC, "Notch", "MVP_PLUS", 9_997_500L, 1_234_567, 5_420, hidden,
                hidden ? null : Instant.parse("2026-11-23T18:00:00Z"), null,
                new BedWarsRaw(4, 120, 98, 310, 102, 145), new SkyWarsRaw(12, 64, 210, 380, 260));
    }

    @BeforeEach
    void setUp() {
        when(repository.findBySourceAndLookupKey(anyString(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(store.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        when(repository.save(any(GameEnrichmentCacheEntry.class))).thenAnswer(inv -> {
            GameEnrichmentCacheEntry e = inv.getArgument(0);
            store.put(e.getSource() + "|" + e.getLookupKey(), e);
            return e;
        });
        when(keyRepository.findByExclusiveFalse()).thenReturn(List.of(new HypixelApiKeyEntry("k")));
        keys = new HypixelApiKeyRotator(keyRepository, new SimpleMeterRegistry());
        EnrichmentCache cache = new EnrichmentCache(repository, properties, MAPPER);
        gameData = new HypixelGameDataService(cache, client, keys, properties);
        service = new HypixelStatsService(cache, client, keys, accounts, gameData, properties);
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        HypixelAccount row = new HypixelAccount(user.getId());
        row.setMinecraftUuid(MC);
        row.setMinecraftName("Notch");
        when(accounts.findById(user.getId())).thenReturn(Optional.of(row));
        when(client.player(MC)).thenReturn(player(false));
        when(client.status(MC)).thenReturn(new HypixelSession(true, "BEDWARS", "EIGHT_TWO", "Lighthouse"));
        when(client.games()).thenReturn(new HypixelGames(List.of(new HypixelGameInfo("BEDWARS", "Bed Wars", false,
                false), new HypixelGameInfo("QUAKECRAFT", "Quakecraft", false, true),
                new HypixelGameInfo("SKYCLASH", "SkyClash", true, false))));
    }

    private Optional<HypixelStats> stats(boolean detail) {
        return service.forMember(user, FetchBudget.UNLIMITED, detail);
    }

    @Test
    void givesTheNetwork_theModes_andWhereThePlayerIs() {
        HypixelStats stats = stats(false).orElseThrow();

        assertThat(stats.status()).isEqualTo(Status.OK);
        assertThat(stats.game()).isEqualTo(HypixelGame.HYPIXEL);
        assertThat(stats.account()).isEqualTo("Notch");
        assertThat(stats.network().level()).isEqualTo(87);
        assertThat(stats.network().karma()).isEqualTo(1_234_567);
        assertThat(stats.network().rank()).isEqualTo("MVP_PLUS");
        assertThat(stats.bedwars().stars()).isEqualTo(4);
        assertThat(stats.bedwars().wins()).isEqualTo(120);
        assertThat(stats.bedwars().finalKills()).as("detail only").isNull();
        assertThat(stats.skywars().stars()).isEqualTo(12);
        assertThat(stats.skywars().kills()).as("detail only").isNull();
        assertThat(stats.online().status()).isEqualTo(Status.OK);
        assertThat(stats.online().online()).isTrue();
        assertThat(stats.online().gameName()).isEqualTo("Bed Wars");
        assertThat(stats.more()).isNull();
    }

    @Test
    void theDetail_fillsTheModesDetails() {
        HypixelStats stats = stats(true).orElseThrow();

        assertThat(stats.bedwars().losses()).isEqualTo(98);
        assertThat(stats.bedwars().finalKills()).isEqualTo(310);
        assertThat(stats.bedwars().bedsBroken()).isEqualTo(145);
        assertThat(stats.skywars().kills()).isEqualTo(380);
        assertThat(stats.skywars().deaths()).isEqualTo(260);
    }

    @Test
    void aHiddenOnlineStatus_isPrivate_withoutAskingIt() {
        when(client.player(MC)).thenReturn(player(true));

        HypixelStats stats = stats(false).orElseThrow();

        assertThat(stats.status()).isEqualTo(Status.OK);
        assertThat(stats.online().status()).isEqualTo(Status.PRIVATE);
        assertThat(stats.online().online()).isNull();
        assertThat(stats.network().lastLogin()).isNull();
        verify(client, never()).status(anyString());
    }

    @Test
    void anAccountThatNeverJoined_isNotFound_andCached() {
        when(client.player(MC)).thenThrow(new HypixelNotFoundException("x"));

        assertThat(stats(false).orElseThrow().status()).isEqualTo(Status.NOT_FOUND);
        assertThat(stats(false).orElseThrow().status()).isEqualTo(Status.NOT_FOUND);

        verify(client, times(1)).player(MC);
    }

    @Test
    void anOutage_isUnavailable_andNeverCached() {
        when(client.player(MC)).thenThrow(new HypixelUnavailableException("down"));

        assertThat(stats(false).orElseThrow().status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(stats(false).orElseThrow().status()).isEqualTo(Status.UNAVAILABLE);

        verify(client, times(2)).player(MC);
    }

    @Test
    void aStatusOutage_leavesThePlayerOk_theOnlineUnavailable_andIsNotCached() {
        when(client.status(MC)).thenThrow(new HypixelUnavailableException("down"));

        HypixelStats stats = stats(false).orElseThrow();
        stats(false);

        assertThat(stats.status()).isEqualTo(Status.OK);
        assertThat(stats.online().status()).isEqualTo(Status.UNAVAILABLE);
        verify(client, times(2)).status(MC);
    }

    @Test
    void anEmptyPool_orTheSourceOff_orNoAccount_givesNothing() {
        when(keyRepository.findByExclusiveFalse()).thenReturn(List.of());
        keys.refreshKeys();
        assertThat(stats(false)).isEmpty();
        assertThat(gameData.forGame(HypixelGame.HYPIXEL, FetchBudget.UNLIMITED)).isEmpty();

        when(keyRepository.findByExclusiveFalse()).thenReturn(List.of(new HypixelApiKeyEntry("k")));
        keys.refreshKeys();
        properties.getHypixel().setEnabled(false);
        assertThat(stats(false)).isEmpty();

        properties.getHypixel().setEnabled(true);
        when(accounts.findById(user.getId())).thenReturn(Optional.empty());
        assertThat(stats(false)).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void onlyMinecraft_mapsToHypixel() {
        DetectedGame minecraft = new DetectedGame("minecraft", GameBinding.SourceType.MINECRAFT, "Minecraft");
        DetectedGame other = new DetectedGame("x", GameBinding.SourceType.STEAM, "Other");

        assertThat(service.forDetected(user, minecraft, null, FetchBudget.UNLIMITED)).isPresent();
        assertThat(service.forDetected(user, other, "121", FetchBudget.UNLIMITED)).isPresent();
        assertThat(service.forDetected(user, other, "1279", FetchBudget.UNLIMITED)).isEmpty();
        assertThat(service.forDetected(user, other, null, FetchBudget.UNLIMITED)).isEmpty();
    }

    @Test
    void thePublicData_listsTheGamesPlayedNow() {
        HypixelPublisher publisher = gameData.forGame(HypixelGame.HYPIXEL, FetchBudget.UNLIMITED).orElseThrow();

        assertThat(publisher.games()).extracting(HypixelPublisher.HypixelPublishedGame::type)
                .containsExactly("BEDWARS");
        assertThat(publisher.toString()).doesNotContain(MC).doesNotContain("Notch");
    }

    @Test
    void theNetworkLevel_followsHypixelsCurve() {
        assertThat(HypixelStatsService.networkLevel(0)).isEqualTo(1);
        assertThat(HypixelStatsService.networkLevel(9_999)).isEqualTo(1);
        assertThat(HypixelStatsService.networkLevel(10_000)).isEqualTo(2);
        assertThat(HypixelStatsService.networkLevel(22_500)).isEqualTo(3);
        assertThat(HypixelStatsService.networkLevel(-5)).isEqualTo(1);
    }
}
