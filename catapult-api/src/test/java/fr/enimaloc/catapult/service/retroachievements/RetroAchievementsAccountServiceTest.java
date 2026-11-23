package fr.enimaloc.catapult.service.retroachievements;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.channel.RetroAchievementsData;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.retroachievements.RetroAchievementsAccount;
import fr.enimaloc.catapult.getter.retroachievements.RetroAchievementsApiKeyRotator;
import fr.enimaloc.catapult.repository.retroachievements.RetroAchievementsAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException;
import fr.enimaloc.catapult.service.enrichment.rastats.RaCredentials;
import fr.enimaloc.catapult.service.enrichment.rastats.RaKeyRejectedException;
import fr.enimaloc.catapult.service.enrichment.rastats.RaUnavailableException;
import fr.enimaloc.catapult.service.enrichment.rastats.RaUser;
import fr.enimaloc.catapult.service.enrichment.rastats.RetroAchievementsClient;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetroAchievementsAccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
    private static final String KEY = "abcdefghij0123456789";
    private static final String ULID = "00003EMFWR7XB8SDPEHB3K56ZQ";
    private static final RaUser SCOTT = new RaUser("Scott", ULID, null, 1, 2, 3, null, null, false);

    private final RetroAchievementsAccountRepository accounts = mock(RetroAchievementsAccountRepository.class);
    private final RetroAchievementsClient client = mock(RetroAchievementsClient.class);
    private final TokenEncryptionService encryption = mock(TokenEncryptionService.class);
    private final RetroAchievementsApiKeyRotator rotator = mock(RetroAchievementsApiKeyRotator.class);
    private final EnrichmentCache cache = mock(EnrichmentCache.class);
    private final ChannelEventPublisher events = mock(ChannelEventPublisher.class);
    private final UserAccount user = new UserAccount();
    private RetroAchievementsAccountService service;

    @BeforeEach
    void setUp() {
        user.setId(UUID.randomUUID());
        when(accounts.findById(user.getId())).thenReturn(Optional.empty());
        when(encryption.encrypt(KEY)).thenReturn("ENC(key)");
        when(encryption.decrypt("ENC(key)")).thenReturn(KEY);
        when(rotator.nextKey()).thenReturn(Optional.of("poolkey0123456789"));
        when(rotator.hasKeys()).thenReturn(true);
        service = new RetroAchievementsAccountService(accounts, client, encryption, Optional.of(rotator), cache,
                events, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private RetroAchievementsAccount existing(String keyEnc) {
        RetroAchievementsAccount row = new RetroAchievementsAccount(user.getId());
        row.setUsername("Scott");
        row.setUserUlid(ULID);
        row.setApiKeyEnc(keyEnc);
        row.setUpdatedAt(NOW.minus(Duration.ofDays(3)));
        when(accounts.findById(user.getId())).thenReturn(Optional.of(row));
        return row;
    }

    private RetroAchievementsAccount saved() {
        ArgumentCaptor<RetroAchievementsAccount> saved = ArgumentCaptor.forClass(RetroAchievementsAccount.class);
        verify(accounts).save(saved.capture());
        return saved.getValue();
    }

    private static void assertProblem(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiProblem.class,
                problem -> assertThat(problem.code()).isEqualTo(code));
    }

    @Test
    void usernameIsStoredWithItsUlidAndTheCardIsPushed() {
        when(client.profile(eq("Scott"), any())).thenReturn(Optional.of(SCOTT));

        service.saveUsername(user, "  Scott ");

        RetroAchievementsAccount row = saved();
        assertThat(row.getUsername()).isEqualTo("Scott");
        assertThat(row.getUserUlid()).isEqualTo(ULID);
        assertThat(row.getUpdatedAt()).isEqualTo(NOW);
        verify(client).profile("Scott", new RaCredentials("poolkey0123456789", false));
        verify(events).retroAchievementsAccountChanged(eq(user.getId()), any(RetroAchievementsData.class));
    }

    @Test
    void theUsernameIsCheckedWithThePersonalKeyWhenThereIsOne() {
        existing("ENC(key)");
        when(client.profile(eq("Scott"), any())).thenReturn(Optional.of(SCOTT));

        service.saveUsername(user, "Scott");

        verify(client).profile("Scott", new RaCredentials(KEY, true));
    }

    @Test
    void aBadUsernameFormIsRefusedBeforeAnyCall() {
        for (String bad : new String[]{null, "", "a", "has space", "x".repeat(33), "bad;name"}) {
            assertProblem(() -> service.saveUsername(user, bad), ErrorCode.BAD_REQUEST);
        }
        verify(client, never()).profile(anyString(), any());
    }

    @Test
    void unknownUsernameIs400AndUnreachableIs503() {
        when(client.profile(eq("ghost"), any())).thenReturn(Optional.empty());
        when(client.profile(eq("down"), any())).thenThrow(new RaUnavailableException("down"));
        when(client.profile(eq("busy"), any())).thenThrow(new UpstreamBudgetExceededException(Duration.ofSeconds(3)));

        assertProblem(() -> service.saveUsername(user, "ghost"), ErrorCode.BAD_REQUEST);
        assertProblem(() -> service.saveUsername(user, "down"), ErrorCode.SERVICE_UNAVAILABLE);
        assertProblem(() -> service.saveUsername(user, "busy"), ErrorCode.SERVICE_UNAVAILABLE);
        verify(accounts, never()).save(any());
    }

    @Test
    void noKeyAtAllIs503() {
        when(rotator.nextKey()).thenReturn(Optional.empty());

        assertProblem(() -> service.saveUsername(user, "Scott"), ErrorCode.SERVICE_UNAVAILABLE);
        verify(client, never()).profile(anyString(), any());
    }

    @Test
    void withoutAPoolTheUsernameIs503() {
        service = new RetroAchievementsAccountService(accounts, client, encryption, Optional.empty(), cache, events,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertProblem(() -> service.saveUsername(user, "Scott"), ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(service.data(user).poolAvailable()).isFalse();
    }

    @Test
    void aRefusedPoolKeyIs503() {
        when(client.profile(eq("Scott"), any())).thenThrow(new RaKeyRejectedException());

        assertProblem(() -> service.saveUsername(user, "Scott"), ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void aRefusedPersonalKeyIsMarkedInvalidAndThePoolTakesOver() {
        RetroAchievementsAccount row = existing("ENC(key)");
        when(client.profile(eq("Scott"), eq(new RaCredentials(KEY, true)))).thenThrow(new RaKeyRejectedException());
        when(client.profile(eq("Scott"), eq(new RaCredentials("poolkey0123456789", false))))
                .thenReturn(Optional.of(SCOTT));

        service.saveUsername(user, "Scott");

        assertThat(row.isKeyInvalid()).isTrue();
        verify(accounts, times(2)).save(row);
    }

    @Test
    void deletingTheUsernameDeletesTheRowAndEvictsTheMember() {
        RetroAchievementsAccount row = existing("ENC(key)");

        service.deleteUsername(user);

        verify(accounts).delete(row);
        verify(cache).evictUser(user.getId());
        verify(events).retroAchievementsAccountChanged(eq(user.getId()), any(RetroAchievementsData.class));
    }

    @Test
    void deletingWhatIsNotThereChangesNothing() {
        service.deleteUsername(user);
        service.deleteApiKey(user);

        verify(accounts, never()).delete(any());
        verify(accounts, never()).save(any());
        verify(events, never()).retroAchievementsAccountChanged(any(), any());
    }

    @Test
    void keyIsStoredEncryptedAndNeverEchoed() {
        existing(null);
        when(client.profile(ULID, new RaCredentials(KEY, true))).thenReturn(Optional.of(SCOTT));

        service.saveApiKey(user, " " + KEY + " ");

        RetroAchievementsAccount row = saved();
        assertThat(row.getApiKeyEnc()).isEqualTo("ENC(key)").isNotEqualTo(KEY);
        assertThat(row.isKeyInvalid()).isFalse();
        ArgumentCaptor<RetroAchievementsData> pushed = ArgumentCaptor.forClass(RetroAchievementsData.class);
        verify(events).retroAchievementsAccountChanged(eq(user.getId()), pushed.capture());
        assertThat(pushed.getValue().hasApiKey()).isTrue();
        assertThat(pushed.getValue().toString()).doesNotContain(KEY);
        assertThat(service.data(user).toString()).doesNotContain(KEY).doesNotContain("ENC(key)");
    }

    @Test
    void refusedKeyIsNotSaved() {
        existing(null);
        when(client.profile(eq(ULID), any())).thenThrow(new RaKeyRejectedException());

        assertProblem(() -> service.saveApiKey(user, KEY), ErrorCode.BAD_REQUEST);

        verify(accounts, never()).save(any());
    }

    @Test
    void keyNeedsAUsername() {
        assertProblem(() -> service.saveApiKey(user, KEY), ErrorCode.BAD_REQUEST);

        verify(client, never()).profile(anyString(), any());
        verify(accounts, never()).save(any());
    }

    @Test
    void aBadKeyFormIsRefusedBeforeAnyCall() {
        existing(null);
        for (String bad : new String[]{null, "", "short", "x".repeat(65), "has space 0123456789"}) {
            assertProblem(() -> service.saveApiKey(user, bad), ErrorCode.BAD_REQUEST);
        }
        verify(client, never()).profile(anyString(), any());
    }

    @Test
    void aKeyForAnAccountRetroAchievementsNoLongerKnowsIs400AndAnOutageIs503() {
        existing(null);
        when(client.profile(eq(ULID), any())).thenReturn(Optional.empty());

        assertProblem(() -> service.saveApiKey(user, KEY), ErrorCode.BAD_REQUEST);

        when(client.profile(eq(ULID), any())).thenThrow(new RaUnavailableException("down"));
        assertProblem(() -> service.saveApiKey(user, KEY), ErrorCode.SERVICE_UNAVAILABLE);
        verify(accounts, never()).save(any());
    }

    @Test
    void deletingTheKeyKeepsTheName() {
        RetroAchievementsAccount row = existing("ENC(key)");
        row.setKeyInvalid(true);

        service.deleteApiKey(user);

        assertThat(row.getApiKeyEnc()).isNull();
        assertThat(row.isKeyInvalid()).isFalse();
        assertThat(row.getUsername()).isEqualTo("Scott");
        verify(accounts).save(row);
    }

    @Test
    void staleWriteIsRetriedThen409() {
        existing(null);
        when(client.profile(eq(ULID), any())).thenReturn(Optional.of(SCOTT));
        when(accounts.save(any())).thenThrow(new OptimisticLockingFailureException("stale"));

        assertProblem(() -> service.saveApiKey(user, KEY), ErrorCode.CONFLICT);

        verify(accounts, times(2)).save(any());
        verify(events, never()).retroAchievementsAccountChanged(any(), any());
    }

    @Test
    void aStaleWriteThatPassesOnTheSecondTryIsSaved() {
        existing(null);
        when(client.profile(eq(ULID), any())).thenReturn(Optional.of(SCOTT));
        when(accounts.save(any())).thenThrow(new OptimisticLockingFailureException("stale"))
                .thenAnswer(call -> call.getArgument(0));

        service.saveApiKey(user, KEY);

        verify(accounts, times(2)).save(any());
    }

    @Test
    void personalKeyWinsThenPoolAfterInvalid() {
        RetroAchievementsAccount row = existing("ENC(key)");

        assertThat(service.credentials(user, Optional.of(row))).contains(new RaCredentials(KEY, true));

        row.setKeyInvalid(true);
        assertThat(service.credentials(user, Optional.of(row)))
                .contains(new RaCredentials("poolkey0123456789", false));
        assertThat(service.credentials(user, Optional.empty()))
                .contains(new RaCredentials("poolkey0123456789", false));

        when(rotator.nextKey()).thenReturn(Optional.empty());
        assertThat(service.credentials(user, Optional.of(row))).isEmpty();
    }

    @Test
    void anUndecryptablePersonalKeyFallsBackToThePool() {
        RetroAchievementsAccount row = existing("garbage");
        when(encryption.decrypt("garbage")).thenThrow(new IllegalStateException("bad"));

        assertThat(service.credentials(user, Optional.of(row))).contains(new RaCredentials("poolkey0123456789", false));
    }

    @Test
    void markKeyInvalidFlagsTheRowAndPushes() {
        RetroAchievementsAccount row = existing("ENC(key)");

        service.markKeyInvalid(user.getId());

        assertThat(row.isKeyInvalid()).isTrue();
        verify(accounts).save(row);
        verify(events).retroAchievementsAccountChanged(eq(user.getId()), any(RetroAchievementsData.class));
    }

    @Test
    void markKeyInvalidIsANoOpWithoutAKeyOrOnceMarked() {
        service.markKeyInvalid(user.getId());
        RetroAchievementsAccount row = existing(null);
        service.markKeyInvalid(user.getId());
        row.setApiKeyEnc("ENC(key)");
        row.setKeyInvalid(true);
        service.markKeyInvalid(user.getId());

        verify(accounts, never()).save(any());
    }

    @Test
    void dataIsBuiltFromTheRowWithoutAnyUpstreamCall() {
        assertThat(service.data(user)).isEqualTo(new RetroAchievementsData(null, null, false, false, true));

        RetroAchievementsAccount row = existing("ENC(key)");
        row.setKeyInvalid(true);
        assertThat(service.data(user)).isEqualTo(new RetroAchievementsData("Scott",
                "https://retroachievements.org/user/Scott", true, true, true));
        verify(client, never()).profile(anyString(), any());
        verify(client, never()).recentlyPlayed(anyString(), anyInt(), any());
    }

    @Test
    void aFailedPushNeverFailsTheWrite() {
        when(client.profile(eq("Scott"), any())).thenReturn(Optional.of(SCOTT));
        doThrow(new IllegalStateException("redis down")).when(events)
                .retroAchievementsAccountChanged(any(), any());

        service.saveUsername(user, "Scott");

        verify(accounts).save(any());
    }
}
