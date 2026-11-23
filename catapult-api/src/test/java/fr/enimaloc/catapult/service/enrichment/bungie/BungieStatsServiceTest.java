package fr.enimaloc.catapult.service.enrichment.bungie;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.bungie.BungieAccount;
import fr.enimaloc.catapult.domain.enrichment.GameEnrichmentCacheEntry;
import fr.enimaloc.catapult.repository.bungie.BungieAccountRepository;
import fr.enimaloc.catapult.repository.enrichment.GameEnrichmentCacheRepository;
import fr.enimaloc.catapult.service.bungie.BungieCredentialsService;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.BungieGame;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BungieStatsServiceTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Instant WHEN = Instant.parse("2026-11-22T21:15:03Z");
    private static final String D2 = "4611686018467284386";

    private final GameEnrichmentCacheRepository repository = mock(GameEnrichmentCacheRepository.class);
    private final BungieClient client = mock(BungieClient.class);
    private final BungieTokenService tokens = mock(BungieTokenService.class);
    private final BungieCredentialsService credentials = mock(BungieCredentialsService.class);
    private final BungieAccountRepository accounts = mock(BungieAccountRepository.class);
    private final Map<String, GameEnrichmentCacheEntry> store = new ConcurrentHashMap<>();
    private final EnrichmentProperties properties = new EnrichmentProperties();
    private BungieStatsService service;
    private UserAccount user;
    private BungieAccount account;

    @BeforeEach
    void setUp() {
        when(repository.findBySourceAndLookupKey(anyString(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(store.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        when(repository.save(any(GameEnrichmentCacheEntry.class))).thenAnswer(inv -> {
            GameEnrichmentCacheEntry e = inv.getArgument(0);
            store.put(e.getSource() + "|" + e.getLookupKey(), e);
            return e;
        });
        EnrichmentCache cache = new EnrichmentCache(repository, properties, MAPPER);
        service = new BungieStatsService(cache, client, tokens, credentials,
                new BungieGameDataService(cache, client, credentials), accounts, properties);
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(credentials.available()).thenReturn(true);
        account = new BungieAccount(user.getId());
        account.setMembershipId("14982373");
        account.setDisplayName("Guardian#0042");
        account.setDestinyMembershipId(D2);
        account.setMembershipType(3);
        account.setRefreshTokenEnc("enc");
        when(accounts.findById(user.getId())).thenAnswer(i -> Optional.ofNullable(account));
        when(tokens.accessToken(user.getId())).thenReturn(Optional.of("token"));
        when(client.profile(3, D2, "token")).thenReturn(profile(false));
        when(client.triumphScore(3, D2, "token")).thenReturn(18_250);
        when(client.definition(eq(BungieStatsService.ACTIVITY), anyLong(), anyString()))
                .thenReturn(new BungieData.Definition("The Glassway", null));
        when(client.definition(eq(BungieStatsService.ACTIVITY_MODE), anyLong(), anyString()))
                .thenReturn(new BungieData.Definition("Vanguard Ops", null));
    }

    private static BungieData.Profile profile(boolean withheld) {
        return new BungieData.Profile(WHEN, 8, 2758726560L, List.of(
                new BungieData.Character("2", 0, 1990, 12_000, WHEN.minusSeconds(86_400), null),
                new BungieData.Character("1", 1, 2010, 61_440, WHEN, "/common/emblem.jpg")),
                new BungieData.Activity("1", 2122313384L, 2394616003L, WHEN.minusSeconds(600)), withheld, false);
    }

    private Optional<BungieStats> stats(boolean detail) {
        return service.forGame(user, BungieGame.DESTINY2, Locale.FRANCE, FetchBudget.UNLIMITED, detail);
    }

    @Test
    void readsTheProfile_withTheMembersToken() {
        BungieStats stats = stats(false).orElseThrow();

        assertThat(stats.status()).isEqualTo(Status.OK);
        assertThat(stats.game()).isEqualTo(BungieGame.DESTINY2);
        assertThat(stats.account()).isEqualTo("Guardian#0042");
        assertThat(stats.more()).isNull();
        BungieStats.Destiny2Stats d2 = stats.destiny2();
        assertThat(d2.platform()).isEqualTo("steam");
        assertThat(d2.guardianRank()).isEqualTo(8);
        assertThat(d2.triumphScore()).isEqualTo(18_250);
        assertThat(d2.lastPlayed()).isEqualTo(WHEN);
        assertThat(d2.character().className()).isEqualTo("hunter");
        assertThat(d2.character().light()).isEqualTo(2010);
        assertThat(d2.character().emblemUrl()).isEqualTo("https://www.bungie.net/common/emblem.jpg");
        assertThat(d2.activity().name()).isEqualTo("The Glassway");
        assertThat(d2.activity().mode()).isEqualTo("Vanguard Ops");
        assertThat(d2.characters()).isNull();
        verify(client).definition(BungieStatsService.ACTIVITY, 2122313384L, "fr");
    }

    @Test
    void theDetail_listsEveryCharacter_lastPlayedFirst() {
        List<BungieStats.Character> characters = stats(true).orElseThrow().destiny2().characters();

        assertThat(characters).extracting(BungieStats.Character::className).containsExactly("hunter", "titan");
    }

    @Test
    void anOutage_isUnavailable_andNeverCached() {
        when(client.profile(anyInt(), anyString(), anyString())).thenThrow(new BungieUnavailableException("down"));

        assertThat(stats(false).orElseThrow().status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(stats(false).orElseThrow().status()).isEqualTo(Status.UNAVAILABLE);

        verify(client, times(2)).profile(anyInt(), anyString(), anyString());
        assertThat(store.keySet()).noneMatch(k -> k.startsWith(EnrichmentSource.BUNGIE_PROFILE.key()));
    }

    @Test
    void notFound_isCachedAsAValue() {
        when(client.profile(anyInt(), anyString(), anyString())).thenThrow(new BungieExceptions.NotFound());

        assertThat(stats(false).orElseThrow().status()).isEqualTo(Status.NOT_FOUND);
        assertThat(stats(false).orElseThrow().status()).isEqualTo(Status.NOT_FOUND);

        verify(client, times(1)).profile(anyInt(), anyString(), anyString());
    }

    @Test
    void aPrivateProfile_isPrivate_fromTheErrorOrTheWithheldComponent() {
        when(client.profile(anyInt(), anyString(), anyString())).thenThrow(new BungieExceptions.Private());
        assertThat(stats(false).orElseThrow().status()).isEqualTo(Status.PRIVATE);

        store.clear();
        org.mockito.Mockito.doReturn(profile(true)).when(client).profile(anyInt(), anyString(), anyString());
        BungieStats stats = stats(false).orElseThrow();
        assertThat(stats.status()).isEqualTo(Status.PRIVATE);
        assertThat(stats.destiny2().guardianRank()).isNull();
    }

    @Test
    void aRefusedAccessToken_isRenewedOnce() {
        when(tokens.accessToken(user.getId())).thenReturn(Optional.of("old"), Optional.of("token"));
        when(client.profile(3, D2, "old")).thenThrow(new BungieExceptions.AuthRefused());

        assertThat(stats(false).orElseThrow().status()).isEqualTo(Status.OK);

        verify(tokens).refused(user.getId(), "old");
    }

    @Test
    void aLinkRefusedDuringTheRead_givesNoObject() {
        when(tokens.accessToken(user.getId())).thenReturn(Optional.empty());

        assertThat(stats(false)).isEmpty();
        verify(client, never()).profile(anyInt(), anyString(), anyString());
    }

    @Test
    void theTriumphScoreIsAPart_itsFailureKeepsTheProfile() {
        when(client.triumphScore(anyInt(), anyString(), anyString())).thenThrow(new BungieUnavailableException("x"));
        when(client.definition(anyString(), anyLong(), anyString())).thenThrow(new BungieUnavailableException("x"));

        BungieStats stats = stats(false).orElseThrow();

        assertThat(stats.status()).isEqualTo(Status.OK);
        assertThat(stats.destiny2().triumphScore()).isNull();
        assertThat(stats.destiny2().activity().name()).isNull();
        assertThat(stats.destiny2().activity().startedAt()).isNotNull();
    }

    @Test
    void noApplication_sourceOff_noLink_orAnInvalidLink_giveNoObject() {
        when(credentials.available()).thenReturn(false);
        assertThat(stats(false)).isEmpty();

        when(credentials.available()).thenReturn(true);
        properties.getBungieProfile().setEnabled(false);
        assertThat(stats(false)).isEmpty();

        properties.getBungieProfile().setEnabled(true);
        account.setInvalidSince(Instant.now());
        assertThat(stats(false)).isEmpty();

        account = null;
        assertThat(stats(false)).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void anAccountWithoutADestinyProfile_isNotFound() {
        account.setDestinyMembershipId(null);
        account.setMembershipType(null);

        assertThat(stats(false).orElseThrow().status()).isEqualTo(Status.NOT_FOUND);
        verifyNoInteractions(client);
    }

    @Test
    void forDetected_onlyAppliesToDestiny2() {
        assertThat(service.forDetected(user, "25657", Locale.ENGLISH, FetchBudget.UNLIMITED)).isPresent();
        assertThat(service.forDetected(user, "123", Locale.ENGLISH, FetchBudget.UNLIMITED)).isEmpty();
        assertThat(service.forDetected(user, null, Locale.ENGLISH, FetchBudget.UNLIMITED)).isEmpty();
    }

    @Test
    void theSeason_isPublicAndCached_offWithoutAnApplication() {
        BungieGameDataService gameData = new BungieGameDataService(new EnrichmentCache(repository, properties, MAPPER),
                client, credentials);
        when(client.currentSeasonHash()).thenReturn(2758726560L);
        when(client.season(2758726560L, "en")).thenReturn(new BungieData.Season(28, "Monument of Triumph",
                Instant.parse("2025-12-02T17:00:00Z"), null));

        BungiePublisher publisher = gameData.forGame(BungieGame.DESTINY2, Locale.ENGLISH, FetchBudget.UNLIMITED)
                .orElseThrow();
        gameData.forGame(BungieGame.DESTINY2, Locale.ENGLISH, FetchBudget.UNLIMITED);

        assertThat(publisher.season().number()).isEqualTo(28);
        assertThat(publisher.season().name()).isEqualTo("Monument of Triumph");
        verify(client, times(1)).currentSeasonHash();
        assertThat(store.keySet()).anyMatch(k -> k.equals("bungie-season|season:en"));

        when(credentials.available()).thenReturn(false);
        assertThat(gameData.forGame(BungieGame.DESTINY2, Locale.ENGLISH, FetchBudget.UNLIMITED)).isEmpty();
        assertThat(gameData.definitionName(BungieStatsService.ACTIVITY, 1L, "en", FetchBudget.UNLIMITED)).isEmpty();
    }

    @Test
    void theSeason_onAnOutage_isAbsent_andNotCached() {
        BungieGameDataService gameData = new BungieGameDataService(new EnrichmentCache(repository, properties, MAPPER),
                client, credentials);
        when(client.currentSeasonHash()).thenThrow(new BungieUnavailableException("down"));

        assertThat(gameData.forGame(BungieGame.DESTINY2, Locale.ENGLISH, FetchBudget.UNLIMITED)).isEmpty();
        assertThat(gameData.forGame(BungieGame.DESTINY2, Locale.ENGLISH, FetchBudget.UNLIMITED)).isEmpty();
        verify(client, times(2)).currentSeasonHash();
    }
}
