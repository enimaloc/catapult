package fr.enimaloc.catapult.service.enrichment.supercell;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.enrichment.GameEnrichmentCacheEntry;
import fr.enimaloc.catapult.domain.supercell.SupercellAccount;
import fr.enimaloc.catapult.domain.supercell.SupercellApiKeyEntry;
import fr.enimaloc.catapult.getter.supercell.SupercellApiKeyRotator;
import fr.enimaloc.catapult.repository.enrichment.GameEnrichmentCacheRepository;
import fr.enimaloc.catapult.repository.supercell.SupercellAccountRepository;
import fr.enimaloc.catapult.repository.supercell.SupercellApiKeyRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellModels.Brawler;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellModels.BsPlayer;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellModels.Card;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellModels.CocPlayer;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellModels.CrPlayer;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellModels.Group;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellModels.Hero;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellModels.SupercellPlayer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

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
import static org.mockito.Mockito.when;

class SupercellStatsServiceTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String TAG = "#2PP";

    private final GameEnrichmentCacheRepository repository = mock(GameEnrichmentCacheRepository.class);
    private final Map<String, GameEnrichmentCacheEntry> store = new ConcurrentHashMap<>();
    private final SupercellClient client = mock(SupercellClient.class);
    private final SupercellApiKeyRepository keyRepository = mock(SupercellApiKeyRepository.class);
    private final SupercellAccountRepository accounts = mock(SupercellAccountRepository.class);
    private final EnrichmentProperties properties = new EnrichmentProperties();
    private SupercellApiKeyRotator keys;
    private SupercellStatsService service;
    private UserAccount user;

    static SupercellPlayer coc() {
        return new SupercellPlayer(TAG, "Chief", new CocPlayer(16, 3, 240, 5200, 5600, 1800, 412, 37, 10, 3900,
                "Legend League", "https://league.example/l.png", new Group("#2Y0", "Catapult"),
                List.of(new Hero("Archer Queen", 92, 95, "home"))), null, null);
    }

    static SupercellPlayer cr() {
        return new SupercellPlayer(TAG, "Chief", null, new CrPlayer(60, 7400, 7600, 3100, 2200, 900, 5600,
                "Legendary Arena", 4, null, List.of(new Card("Hog Rider", 14, 15))), null);
    }

    static SupercellPlayer bs() {
        return new SupercellPlayer(TAG, "Chief", null, null, new BsPlayer(180, 31000, 31500, 2400, 800, 300,
                new Group("#2Y0", "Catapult"), List.of(new Brawler("SHELLY", 11, 30, 900, 1000))));
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
        when(keyRepository.findByExclusiveFalse()).thenReturn(List.of(new SupercellApiKeyEntry("k1", "clash-of-clans"),
                new SupercellApiKeyEntry("k2", "clash-royale"), new SupercellApiKeyEntry("k3", "brawl-stars")));
        keys = new SupercellApiKeyRotator(keyRepository, new SimpleMeterRegistry());
        service = new SupercellStatsService(new EnrichmentCache(repository, properties, MAPPER), client, keys, accounts,
                properties);
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        SupercellAccount row = new SupercellAccount(user.getId());
        for (SupercellGame game : SupercellGame.values()) {
            row.set(game, TAG, "Declared");
        }
        when(accounts.findById(user.getId())).thenReturn(Optional.of(row));
        when(client.player(SupercellGame.CLASH_OF_CLANS, TAG)).thenReturn(coc());
        when(client.player(SupercellGame.CLASH_ROYALE, TAG)).thenReturn(cr());
        when(client.player(SupercellGame.BRAWL_STARS, TAG)).thenReturn(bs());
    }

    private Optional<SupercellStats> stats(SupercellGame game, boolean detail) {
        return service.forGame(user, game, FetchBudget.UNLIMITED, detail);
    }

    @Test
    void clashOfClans_givesTheVillage_andTheDetailOnlyFieldsInTheDetail() {
        SupercellStats aggregate = stats(SupercellGame.CLASH_OF_CLANS, false).orElseThrow();

        assertThat(aggregate.status()).isEqualTo(Status.OK);
        assertThat(aggregate.game()).isEqualTo(SupercellGame.CLASH_OF_CLANS);
        assertThat(aggregate.account()).isEqualTo("Chief");
        assertThat(aggregate.tag()).isEqualTo(TAG);
        assertThat(aggregate.clashOfClans().townHallLevel()).isEqualTo(16);
        assertThat(aggregate.clashOfClans().league()).isEqualTo("Legend League");
        assertThat(aggregate.clashOfClans().clan().name()).isEqualTo("Catapult");
        assertThat(aggregate.clashOfClans().heroes()).isNull();
        assertThat(aggregate.clashOfClans().attackWins()).isNull();
        assertThat(aggregate.clashRoyale()).isNull();
        assertThat(aggregate.brawlStars()).isNull();

        SupercellStats detail = stats(SupercellGame.CLASH_OF_CLANS, true).orElseThrow();
        assertThat(detail.clashOfClans().heroes()).extracting(SupercellStats.Hero::name).containsExactly("Archer Queen");
        assertThat(detail.clashOfClans().attackWins()).isEqualTo(412);
        // the second read came from the cache
        verify(client, times(1)).player(SupercellGame.CLASH_OF_CLANS, TAG);
    }

    @Test
    void clashRoyale_andBrawlStars_giveTheirOwnObjects() {
        SupercellStats royale = stats(SupercellGame.CLASH_ROYALE, true).orElseThrow();
        assertThat(royale.clashRoyale().arena()).isEqualTo("Legendary Arena");
        assertThat(royale.clashRoyale().clan()).isNull();
        assertThat(royale.clashRoyale().currentDeck()).extracting(SupercellStats.Card::name).containsExactly("Hog Rider");
        assertThat(royale.clashRoyale().battleCount()).isEqualTo(5600);

        SupercellStats brawl = stats(SupercellGame.BRAWL_STARS, false).orElseThrow();
        assertThat(brawl.brawlStars().trioVictories()).isEqualTo(2400);
        assertThat(brawl.brawlStars().brawlers()).isNull();
        assertThat(stats(SupercellGame.BRAWL_STARS, true).orElseThrow().brawlStars().brawlers()).hasSize(1);
    }

    @Test
    void anUnknownTag_isNotFound_andCached() {
        when(client.player(SupercellGame.CLASH_OF_CLANS, TAG)).thenThrow(new SupercellNotFoundException("gone"));

        SupercellStats stats = stats(SupercellGame.CLASH_OF_CLANS, false).orElseThrow();
        assertThat(stats.status()).isEqualTo(Status.NOT_FOUND);
        assertThat(stats.account()).isEqualTo("Declared");
        assertThat(stats.clashOfClans()).isNull();

        assertThat(stats(SupercellGame.CLASH_OF_CLANS, false).orElseThrow().status()).isEqualTo(Status.NOT_FOUND);
        verify(client, times(1)).player(SupercellGame.CLASH_OF_CLANS, TAG);
    }

    @Test
    void anOutage_isUnavailable_neverCached_andNotAskedAgainAtOnce() {
        when(client.player(SupercellGame.CLASH_OF_CLANS, TAG)).thenThrow(new SupercellUnavailableException("down"));

        assertThat(stats(SupercellGame.CLASH_OF_CLANS, false).orElseThrow().status()).isEqualTo(Status.UNAVAILABLE);
        // within the backoff the tag is left alone: still unavailable, no second call
        assertThat(stats(SupercellGame.CLASH_OF_CLANS, false).orElseThrow().status()).isEqualTo(Status.UNAVAILABLE);
        verify(client, times(1)).player(SupercellGame.CLASH_OF_CLANS, TAG);
        assertThat(store).isEmpty();
        // another game of the same member is not held back
        assertThat(stats(SupercellGame.CLASH_ROYALE, false).orElseThrow().status()).isEqualTo(Status.OK);
    }

    @Test
    void theSourceOff_orNoKeyOfTheGame_orNoTag_givesNothing() {
        properties.getSupercell().setEnabled(false);
        assertThat(stats(SupercellGame.CLASH_OF_CLANS, false)).isEmpty();
        properties.getSupercell().setEnabled(true);

        properties.getSupercellClashOfClans().setEnabled(false);
        assertThat(stats(SupercellGame.CLASH_OF_CLANS, false)).isEmpty();
        properties.getSupercellClashOfClans().setEnabled(true);

        when(keyRepository.findByExclusiveFalse()).thenReturn(List.of(new SupercellApiKeyEntry("k2", "clash-royale")));
        keys.refreshKeys();
        assertThat(stats(SupercellGame.CLASH_OF_CLANS, false)).isEmpty();
        assertThat(service.available(SupercellGame.CLASH_ROYALE)).isTrue();

        SupercellAccount row = new SupercellAccount(user.getId());
        row.set(SupercellGame.BRAWL_STARS, TAG, "x");
        when(accounts.findById(user.getId())).thenReturn(Optional.of(row));
        assertThat(stats(SupercellGame.CLASH_ROYALE, false)).isEmpty();
        when(accounts.findById(user.getId())).thenReturn(Optional.empty());
        assertThat(stats(SupercellGame.CLASH_ROYALE, false)).isEmpty();
        verify(client, never()).player(any(), any());
    }

    @Test
    void theDetectedGame_isFoundByItsName_orByAConfiguredIgdbId() {
        assertThat(service.gameOf(null, "Clash Royale")).contains(SupercellGame.CLASH_ROYALE);
        assertThat(service.gameOf("1", "Brawl Stars")).contains(SupercellGame.BRAWL_STARS);
        assertThat(service.gameOf("1", "Minecraft")).isEmpty();
        properties.getSupercell().setClashOfClansIgdbId("11");
        properties.getSupercell().setClashRoyaleIgdbId("12");
        properties.getSupercell().setBrawlStarsIgdbId("13");
        assertThat(service.gameOf("11", null)).contains(SupercellGame.CLASH_OF_CLANS);
        assertThat(service.gameOf("12", null)).contains(SupercellGame.CLASH_ROYALE);
        assertThat(service.gameOf("13", "x")).contains(SupercellGame.BRAWL_STARS);

        assertThat(service.forDetected(user, null, "Brawl Stars", FetchBudget.UNLIMITED).orElseThrow().game())
                .isEqualTo(SupercellGame.BRAWL_STARS);
        assertThat(service.forDetected(user, null, "Hay Day", FetchBudget.UNLIMITED)).isEmpty();
    }

    @Test
    void theCacheKey_isTheMemberAndTheTag_andEachGameHasItsSource() {
        assertThat(SupercellStatsService.cacheKey(user.getId(), TAG)).isEqualTo("u:" + user.getId() + ":" + TAG);
        assertThat(SupercellStatsService.source(SupercellGame.CLASH_ROYALE).key()).isEqualTo("supercell-clash-royale");
        assertThat(SupercellStatsService.source(SupercellGame.BRAWL_STARS).personal()).isTrue();
        assertThat(SupercellStatsService.source(SupercellGame.CLASH_OF_CLANS).backsOff()).isTrue();
    }
}
