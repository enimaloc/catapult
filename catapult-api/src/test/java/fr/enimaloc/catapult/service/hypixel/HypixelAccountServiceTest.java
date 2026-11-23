package fr.enimaloc.catapult.service.hypixel;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.channel.HypixelData;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.hypixel.HypixelAccount;
import fr.enimaloc.catapult.domain.hypixel.HypixelApiKeyEntry;
import fr.enimaloc.catapult.getter.hypixel.HypixelApiKeyRotator;
import fr.enimaloc.catapult.repository.hypixel.HypixelAccountRepository;
import fr.enimaloc.catapult.repository.hypixel.HypixelApiKeyRepository;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.MemberFetchBudgets;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelClient;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.HypixelPlayer;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.MinecraftProfile;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelNotFoundException;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelUnavailableException;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HypixelAccountServiceTest {

    static final String MC = "069a79f444e94726a5befca90e38aaf5";

    private final HypixelAccountRepository accounts = mock(HypixelAccountRepository.class);
    private final HypixelClient client = mock(HypixelClient.class);
    private final HypixelApiKeyRepository keyRepository = mock(HypixelApiKeyRepository.class);
    private final EnrichmentCache cache = mock(EnrichmentCache.class);
    private final ChannelEventPublisher events = mock(ChannelEventPublisher.class);
    private final EnrichmentProperties properties = new EnrichmentProperties();
    private MemberFetchBudgets budgets;
    private HypixelApiKeyRotator keys;
    private HypixelAccountService service;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        when(keyRepository.findByExclusiveFalse()).thenReturn(List.of(new HypixelApiKeyEntry("k")));
        keys = new HypixelApiKeyRotator(keyRepository, new SimpleMeterRegistry());
        budgets = new MemberFetchBudgets(properties);
        service = new HypixelAccountService(accounts, client, keys, properties, cache, budgets, events,
                Clock.fixed(Instant.parse("2026-11-23T10:00:00Z"), ZoneOffset.UTC));
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(accounts.findById(user.getId())).thenReturn(Optional.empty());
        when(client.profileByName("Notch")).thenReturn(new MinecraftProfile(MC, "Notch"));
        when(client.player(MC)).thenReturn(new HypixelPlayer(MC, "Notch", null, 0, null, null, false, null, null,
                null, null));
    }

    private HypixelAccount saved() {
        ArgumentCaptor<HypixelAccount> row = ArgumentCaptor.forClass(HypixelAccount.class);
        verify(accounts).save(row.capture());
        return row.getValue();
    }

    private static void assertProblem(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiProblem.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void aName_isLookedUpAtMojang_thenCheckedWithHypixel_andSaved() {
        service.save(user, "  Notch ");

        HypixelAccount row = saved();
        assertThat(row.getMinecraftUuid()).isEqualTo(MC);
        assertThat(row.getMinecraftName()).isEqualTo("Notch");
        assertThat(row.getUpdatedAt()).isEqualTo(Instant.parse("2026-11-23T10:00:00Z"));
        verify(cache).evictUser(user.getId(), HypixelAccountService.SOURCES);
        ArgumentCaptor<HypixelData> data = ArgumentCaptor.forClass(HypixelData.class);
        verify(events).hypixelAccountChanged(eq(user.getId()), data.capture());
    }

    @Test
    void aDashedUuid_isTakenWithoutMojang() {
        service.save(user, "069A79F4-44E9-4726-A5BE-FCA90E38AAF5");

        assertThat(saved().getMinecraftUuid()).isEqualTo(MC);
        verify(client, never()).profileByName(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ab", "a b c", "name!", "12345678901234567", "069a79f444e94726a5befca90e38aaf"})
    void aMalformedEntry_is400_andCostsNothing(String entry) {
        properties.setMemberPerMinute(1);

        assertProblem(() -> service.save(user, entry), ErrorCode.BAD_REQUEST);

        verifyNoInteractions(client);
        assertThat(budgets.forMember(user.getId()).tryAcquire()).as("the refresh is still there").isTrue();
    }

    @Test
    void aSpentBudget_is429_beforeAnyCall() {
        properties.setMemberPerMinute(1);
        budgets.forMember(user.getId()).tryAcquire();

        assertProblem(() -> service.save(user, "Notch"), ErrorCode.TOO_MANY_REQUESTS);

        verifyNoInteractions(client);
    }

    @Test
    void anUnknownName_orAnAccountThatNeverJoined_is400_andNothingIsWritten() {
        when(client.profileByName("ghost")).thenThrow(new HypixelNotFoundException("x"));
        when(client.player(MC)).thenThrow(new HypixelNotFoundException("x"));

        assertProblem(() -> service.save(user, "ghost"), ErrorCode.BAD_REQUEST);
        assertProblem(() -> service.save(user, MC), ErrorCode.BAD_REQUEST);

        verify(accounts, never()).save(any());
    }

    @Test
    void anOutage_orTheSourceOff_is503_andNothingIsWritten() {
        when(client.player(MC)).thenThrow(new HypixelUnavailableException("down"));
        assertProblem(() -> service.save(user, MC), ErrorCode.SERVICE_UNAVAILABLE);

        when(client.profileByName("Notch")).thenThrow(new HypixelUnavailableException("down"));
        assertProblem(() -> service.save(user, "Notch"), ErrorCode.SERVICE_UNAVAILABLE);

        when(keyRepository.findByExclusiveFalse()).thenReturn(List.of());
        keys.refreshKeys();
        assertProblem(() -> service.save(user, MC), ErrorCode.SERVICE_UNAVAILABLE);

        verify(accounts, never()).save(any());
    }

    @Test
    void aWriteCrossingAnother_isRedoneOnce_thenIs409() {
        when(accounts.save(any())).thenThrow(new OptimisticLockingFailureException("x"))
                .thenReturn(null);
        service.save(user, MC);
        verify(accounts, times(2)).save(any());

        when(accounts.save(any())).thenThrow(new OptimisticLockingFailureException("x"));
        assertProblem(() -> service.save(user, MC), ErrorCode.CONFLICT);
    }

    @Test
    void delete_removesTheRow_andForgetsTheCache() {
        HypixelAccount row = new HypixelAccount(user.getId());
        row.setMinecraftUuid(MC);
        row.setMinecraftName("Notch");
        when(accounts.findById(user.getId())).thenReturn(Optional.of(row));

        service.delete(user);

        verify(accounts).delete(row);
        verify(cache).evictUser(user.getId(), HypixelAccountService.SOURCES);
        verify(events).hypixelAccountChanged(eq(user.getId()), any());
    }

    @Test
    void data_saysWhatIsDeclared_andWhetherTheSourceServes() {
        assertThat(service.data(user)).isEqualTo(new HypixelData(null, null, true));

        HypixelAccount row = new HypixelAccount(user.getId());
        row.setMinecraftUuid(MC);
        row.setMinecraftName("Notch");
        when(accounts.findById(user.getId())).thenReturn(Optional.of(row));
        when(keyRepository.findByExclusiveFalse()).thenReturn(List.of());
        keys.refreshKeys();

        assertThat(service.data(user)).isEqualTo(new HypixelData("Notch", MC, false));
        verifyNoInteractions(client);
    }

    @Test
    void aPushThatFails_neverFailsTheSave() {
        org.mockito.Mockito.doThrow(new RuntimeException("redis")).when(events).hypixelAccountChanged(any(), any());

        service.save(user, MC);

        verify(accounts).save(any());
    }
}
