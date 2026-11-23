package fr.enimaloc.catapult.service.faceit;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.channel.FaceitData;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.faceit.FaceitAccount;
import fr.enimaloc.catapult.domain.faceit.FaceitApiKeyEntry;
import fr.enimaloc.catapult.getter.faceit.FaceitApiKeyRotator;
import fr.enimaloc.catapult.repository.faceit.FaceitAccountRepository;
import fr.enimaloc.catapult.repository.faceit.FaceitApiKeyRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.faceit.FaceitClient;
import fr.enimaloc.catapult.service.enrichment.faceit.FaceitModels.Player;
import fr.enimaloc.catapult.service.enrichment.faceit.FaceitNotFoundException;
import fr.enimaloc.catapult.service.enrichment.faceit.FaceitUnavailableException;
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

class FaceitAccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");

    private final FaceitAccountRepository accounts = mock(FaceitAccountRepository.class);
    private final FaceitClient client = mock(FaceitClient.class);
    private final FaceitApiKeyRepository keyRepository = mock(FaceitApiKeyRepository.class);
    private final EnrichmentCache cache = mock(EnrichmentCache.class);
    private final ChannelEventPublisher events = mock(ChannelEventPublisher.class);
    private final EnrichmentProperties properties = new EnrichmentProperties();
    private final AtomicReference<FaceitAccount> stored = new AtomicReference<>();
    private FaceitApiKeyRotator keys;
    private FaceitAccountService service;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        when(keyRepository.findByExclusiveFalse()).thenReturn(List.of(new FaceitApiKeyEntry("k")));
        keys = new FaceitApiKeyRotator(keyRepository, new SimpleMeterRegistry());
        service = new FaceitAccountService(accounts, client, keys, properties, cache, events,
                Clock.fixed(NOW, ZoneOffset.UTC));
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(accounts.findById(user.getId())).thenAnswer(inv -> Optional.ofNullable(stored.get()));
        when(accounts.save(any(FaceitAccount.class))).thenAnswer(inv -> {
            stored.set(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(client.playerByNickname(anyString())).thenAnswer(inv ->
                new Player("id-" + inv.getArgument(0), inv.getArgument(0), null, null, null, null));
    }

    @Test
    void save_checksTheNicknameWithFaceit_andStoresThePlayerId() {
        service.save(user, "  Catapult ");

        verify(client).playerByNickname("Catapult");
        FaceitAccount row = stored.get();
        assertThat(row.getPlayerId()).isEqualTo("id-Catapult");
        assertThat(row.getNickname()).isEqualTo("Catapult");
        assertThat(row.getUpdatedAt()).isEqualTo(NOW);
        verify(events).faceitAccountChanged(user.getId(),
                new FaceitData("Catapult", "https://www.faceit.com/en/players/Catapult", true));
    }

    @Test
    void save_keepsFaceitsSpelling() {
        when(client.playerByNickname(anyString())).thenReturn(new Player("p", "CataPult", null, null, null, null));

        service.save(user, "catapult");

        assertThat(stored.get().getNickname()).isEqualTo("CataPult");
    }

    @Test
    void aNewOrChangedPlayer_forgetsTheFaceitCache_theSameOneDoesNot() {
        service.save(user, "Catapult");
        service.save(user, "Catapult");
        service.save(user, "Other");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<EnrichmentSource>> sources = ArgumentCaptor.forClass(Collection.class);
        verify(cache, times(2)).evictUser(eq(user.getId()), sources.capture());
        assertThat(sources.getValue()).containsExactlyInAnyOrder(EnrichmentSource.FACEIT_PLAYER,
                EnrichmentSource.FACEIT_STATS, EnrichmentSource.FACEIT_LAST_MATCH);
        verify(cache, never()).evictUser(user.getId());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "a", "has space", "semi;colon", "way-too-long-nickname-for-faceit-123"})
    void aMalformedNickname_isA400_withoutAskingFaceit(String nickname) {
        assertThatThrownBy(() -> service.save(user, nickname)).isInstanceOfSatisfying(ApiProblem.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.BAD_REQUEST));
        verifyNoInteractions(client);
        assertThat(stored.get()).isNull();
    }

    @Test
    void anUnknownNickname_isA400_andWritesNothing() {
        when(client.playerByNickname("Ghost")).thenThrow(new FaceitNotFoundException("unknown"));

        assertThatThrownBy(() -> service.save(user, "Ghost")).isInstanceOfSatisfying(ApiProblem.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.BAD_REQUEST));
        assertThat(stored.get()).isNull();
        verifyNoInteractions(events);
    }

    @Test
    void faceitOutOfReach_theSourceOff_orAnEmptyPool_isA503_andWritesNothing() {
        when(client.playerByNickname("Later")).thenThrow(new FaceitUnavailableException("down"));
        assertThatThrownBy(() -> service.save(user, "Later")).isInstanceOfSatisfying(ApiProblem.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));

        properties.getFaceit().setEnabled(false);
        assertThatThrownBy(() -> service.save(user, "Catapult")).isInstanceOfSatisfying(ApiProblem.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));

        properties.getFaceit().setEnabled(true);
        when(keyRepository.findByExclusiveFalse()).thenReturn(List.of());
        keys.refreshKeys();
        assertThatThrownBy(() -> service.save(user, "Catapult")).isInstanceOfSatisfying(ApiProblem.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));

        assertThat(stored.get()).isNull();
        verify(client, times(1)).playerByNickname(any());
    }

    @Test
    void aWriteCrossingAnother_isRedoneOnce_thenIsA409() {
        doThrow(new OptimisticLockingFailureException("crossed")).doAnswer(inv -> {
            stored.set(inv.getArgument(0));
            return inv.getArgument(0);
        }).when(accounts).save(any(FaceitAccount.class));
        service.save(user, "Catapult");
        assertThat(stored.get().getPlayerId()).isEqualTo("id-Catapult");

        doThrow(new OptimisticLockingFailureException("crossed")).when(accounts).save(any(FaceitAccount.class));
        assertThatThrownBy(() -> service.save(user, "Other")).isInstanceOfSatisfying(ApiProblem.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void delete_removesTheRow_forgetsTheCache_andTellsThePage() {
        service.save(user, "Catapult");
        FaceitAccount row = stored.get();

        service.delete(user);

        verify(accounts).delete(row);
        verify(cache, times(2)).evictUser(eq(user.getId()), any());
        // once for the save, once for the delete
        verify(events, times(2)).faceitAccountChanged(eq(user.getId()), any());
    }

    @Test
    void delete_withoutANickname_stillForgetsTheCache_butTellsNothing() {
        service.delete(user);

        verify(accounts, never()).delete(any());
        verify(cache).evictUser(eq(user.getId()), any());
        verifyNoInteractions(events);
    }

    @Test
    void data_isBuiltFromTheRowOnly() {
        assertThat(service.data(user)).isEqualTo(new FaceitData(null, null, true));
        FaceitAccount row = new FaceitAccount(user.getId());
        row.setNickname("Le Chat");
        row.setPlayerId("p");
        stored.set(row);

        assertThat(service.data(user))
                .isEqualTo(new FaceitData("Le Chat", "https://www.faceit.com/en/players/Le%20Chat", true));
        properties.getFaceit().setEnabled(false);
        assertThat(service.data(user).available()).isFalse();
        verifyNoInteractions(client);
    }

    @Test
    void aPushThatFails_doesNotFailTheWrite() {
        doThrow(new IllegalStateException("redis down")).when(events).faceitAccountChanged(any(), any());

        service.save(user, "Catapult");

        assertThat(stored.get()).isNotNull();
    }
}
