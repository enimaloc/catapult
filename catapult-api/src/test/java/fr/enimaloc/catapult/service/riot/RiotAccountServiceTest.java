package fr.enimaloc.catapult.service.riot;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.channel.RiotData;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.riot.RiotAccount;
import fr.enimaloc.catapult.domain.riot.RiotApiKeyEntry;
import fr.enimaloc.catapult.getter.riot.RiotApiKeyRotator;
import fr.enimaloc.catapult.repository.riot.RiotAccountRepository;
import fr.enimaloc.catapult.repository.riot.RiotApiKeyRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.riot.RiotClient;
import fr.enimaloc.catapult.service.enrichment.riot.RiotModels.RiotAccountRef;
import fr.enimaloc.catapult.service.enrichment.riot.RiotNotFoundException;
import fr.enimaloc.catapult.service.enrichment.riot.RiotPlatform.Cluster;
import fr.enimaloc.catapult.service.enrichment.riot.RiotUnavailableException;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RiotAccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");

    private final RiotAccountRepository accounts = mock(RiotAccountRepository.class);
    private final RiotClient client = mock(RiotClient.class);
    private final RiotApiKeyRepository keyRepository = mock(RiotApiKeyRepository.class);
    private final EnrichmentCache cache = mock(EnrichmentCache.class);
    private final ChannelEventPublisher events = mock(ChannelEventPublisher.class);
    private final EnrichmentProperties properties = new EnrichmentProperties();
    private final AtomicReference<RiotAccount> stored = new AtomicReference<>();
    private RiotApiKeyRotator keys;
    private RiotAccountService service;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        when(keyRepository.findByExclusiveFalse()).thenReturn(List.of(new RiotApiKeyEntry("RGAPI-k", null)));
        keys = new RiotApiKeyRotator(keyRepository, new SimpleMeterRegistry());
        service = new RiotAccountService(accounts, client, keys, properties, cache, events,
                Clock.fixed(NOW, ZoneOffset.UTC));
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(accounts.findById(user.getId())).thenAnswer(inv -> Optional.ofNullable(stored.get()));
        when(accounts.save(any(RiotAccount.class))).thenAnswer(inv -> {
            stored.set(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(client.accountByRiotId(any(), anyString(), anyString())).thenAnswer(inv ->
                new RiotAccountRef("puuid-" + inv.getArgument(1), inv.getArgument(1), inv.getArgument(2)));
    }

    @Test
    void save_checksTheRiotIdWithRiot_andStoresThePuuid() {
        service.save(user, "  Catapult #EUW ", "EUW1");

        verify(client).accountByRiotId(Cluster.EUROPE, "Catapult", "EUW");
        RiotAccount row = stored.get();
        assertThat(row.getPuuid()).isEqualTo("puuid-Catapult");
        assertThat(row.riotId()).isEqualTo("Catapult#EUW");
        assertThat(row.getPlatform()).isEqualTo("euw1");
        assertThat(row.getRegion()).isEqualTo("europe");
        assertThat(row.getUpdatedAt()).isEqualTo(NOW);
        verify(events).riotAccountChanged(user.getId(), new RiotData("Catapult#EUW", "euw1", true));
    }

    @Test
    void save_keepsRiotsSpelling() {
        when(client.accountByRiotId(any(), anyString(), anyString()))
                .thenReturn(new RiotAccountRef("p", "CataPult", "euw"));

        service.save(user, "catapult#EUW", "euw1");

        assertThat(stored.get().riotId()).isEqualTo("CataPult#euw");
    }

    @Test
    void aNewOrChangedRiotId_forgetsTheRiotCache_theSameOneDoesNot() {
        service.save(user, "Catapult#EUW", "euw1");
        service.save(user, "Catapult#EUW", "euw1");
        service.save(user, "Catapult#EUW", "na1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<EnrichmentSource>> sources = ArgumentCaptor.forClass(Collection.class);
        verify(cache, times(2)).evictUser(eq(user.getId()), sources.capture());
        assertThat(sources.getValue()).containsExactlyInAnyOrder(EnrichmentSource.RIOT_LOL_SUMMONER,
                EnrichmentSource.RIOT_LOL_LEAGUE, EnrichmentSource.RIOT_LOL_LIVE, EnrichmentSource.RIOT_TFT_LEAGUE);
        verify(cache, never()).evictUser(user.getId());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "Catapult", "#EUW", "Catapult#", "ab#EUW", "Catapult#E", "Catapult#EUWEST",
            "WayTooLongGameName17#EUW", "Cata/pult#EUW", "Catapult#E-W"})
    void aMalformedRiotId_is400_withoutAskingRiot(String riotId) {
        assertThatThrownBy(() -> service.save(user, riotId, "euw1"))
                .isInstanceOfSatisfying(ApiProblem.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.BAD_REQUEST));
        verifyNoInteractions(client);
    }

    @Test
    void aRiotIdWithSpacesOrAccents_isAccepted() {
        service.save(user, "Le Château#1234", "euw1");

        verify(client).accountByRiotId(Cluster.EUROPE, "Le Château", "1234");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "europe", "euw", "mars1"})
    void anUnknownPlatform_is400(String platform) {
        assertThatThrownBy(() -> service.save(user, "Catapult#EUW", platform))
                .isInstanceOfSatisfying(ApiProblem.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.BAD_REQUEST));
        verifyNoInteractions(client);
    }

    @Test
    void aRiotIdRiotDoesNotKnow_is400_andNothingIsWritten() {
        when(client.accountByRiotId(any(), anyString(), anyString())).thenThrow(new RiotNotFoundException("no"));

        assertThatThrownBy(() -> service.save(user, "Ghost#0000", "euw1"))
                .isInstanceOfSatisfying(ApiProblem.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.BAD_REQUEST));
        verify(accounts, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void riotOutOfReach_is503_andNothingIsWritten() {
        when(client.accountByRiotId(any(), anyString(), anyString())).thenThrow(new RiotUnavailableException("down"));

        assertThatThrownBy(() -> service.save(user, "Catapult#EUW", "euw1"))
                .isInstanceOfSatisfying(ApiProblem.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        verify(accounts, never()).save(any());
    }

    @Test
    void anEmptyPool_orNoKeyForTheCluster_orTheSourceOff_is503_withoutAskingRiot() {
        when(keyRepository.findByExclusiveFalse()).thenReturn(List.of(new RiotApiKeyEntry("RGAPI-k", "americas")));
        keys.refreshKeys();
        assertThatThrownBy(() -> service.save(user, "Catapult#EUW", "euw1")).isInstanceOfSatisfying(ApiProblem.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));

        when(keyRepository.findByExclusiveFalse()).thenReturn(List.of());
        keys.refreshKeys();
        assertThatThrownBy(() -> service.save(user, "Catapult#EUW", "na1")).isInstanceOf(ApiProblem.class);

        when(keyRepository.findByExclusiveFalse()).thenReturn(List.of(new RiotApiKeyEntry("RGAPI-k", null)));
        keys.refreshKeys();
        properties.getRiot().setEnabled(false);
        assertThatThrownBy(() -> service.save(user, "Catapult#EUW", "na1")).isInstanceOf(ApiProblem.class);

        verifyNoInteractions(client);
    }

    @Test
    void aWriteCrossingAnother_isRedoneOnce_thenIs409() {
        when(accounts.save(any(RiotAccount.class)))
                .thenThrow(new OptimisticLockingFailureException("stale"))
                .thenAnswer(inv -> inv.getArgument(0));
        service.save(user, "Catapult#EUW", "euw1");
        verify(accounts, times(2)).save(any());

        when(accounts.save(any(RiotAccount.class))).thenThrow(new OptimisticLockingFailureException("stale"));
        assertThatThrownBy(() -> service.save(user, "Catapult#EUW", "euw1"))
                .isInstanceOfSatisfying(ApiProblem.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void delete_removesTheRow_forgetsTheCache_andTellsThePages() {
        service.save(user, "Catapult#EUW", "euw1");
        RiotAccount row = stored.get();
        org.mockito.Mockito.doAnswer(inv -> {
            stored.set(null);
            return null;
        }).when(accounts).delete(row);

        service.delete(user);

        verify(accounts).delete(row);
        verify(cache, times(2)).evictUser(eq(user.getId()), any());
        verify(events).riotAccountChanged(user.getId(), new RiotData(null, null, true));
    }

    @Test
    void delete_withoutARow_tellsNoOne() {
        service.delete(user);

        verify(accounts, never()).delete(any());
        verifyNoInteractions(events);
    }

    @Test
    void aPushThatFails_doesNotFailTheWrite() {
        doThrow(new IllegalStateException("redis")).when(events).riotAccountChanged(any(), any());

        service.save(user, "Catapult#EUW", "euw1");

        assertThat(stored.get()).isNotNull();
    }

    @Test
    void data_tellsTheCard_withoutCallingRiot() {
        assertThat(service.data(user)).isEqualTo(new RiotData(null, null, true));
        service.save(user, "Catapult#EUW", "euw1");
        assertThat(service.data(user)).isEqualTo(new RiotData("Catapult#EUW", "euw1", true));

        when(keyRepository.findByExclusiveFalse()).thenReturn(List.of());
        keys.refreshKeys();
        assertThat(service.data(user).available()).isFalse();
        verify(client, times(1)).accountByRiotId(any(), anyString(), anyString());
    }
}
