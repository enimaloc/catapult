package fr.enimaloc.catapult.service.speedrun;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.channel.SpeedrunData;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.speedrun.SpeedrunAccount;
import fr.enimaloc.catapult.repository.speedrun.SpeedrunAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunClient;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunIdentity;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunIdentityResolver;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunRateLimiter;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunUnavailableException;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SpeedrunAccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    private static final SpeedrunUser KEY_OWNER =
            new SpeedrunUser("k1", "KeyOwner", "https://www.speedrun.com/users/KeyOwner", null);

    private final SpeedrunAccountRepository accounts = mock(SpeedrunAccountRepository.class);
    private final SpeedrunClient client = mock(SpeedrunClient.class);
    private final SpeedrunRateLimiter limiter = mock(SpeedrunRateLimiter.class);
    private final TokenEncryptionService encryption = mock(TokenEncryptionService.class);
    private final SpeedrunIdentityResolver resolver = mock(SpeedrunIdentityResolver.class);
    private final EnrichmentCache cache = mock(EnrichmentCache.class);
    private final UserAccount user = new UserAccount();
    private SpeedrunAccountService service;

    @BeforeEach
    void setUp() {
        user.setId(UUID.randomUUID());
        when(accounts.findById(user.getId())).thenReturn(Optional.empty());
        when(limiter.member()).thenReturn(FetchBudget.UNLIMITED);
        when(encryption.encrypt("abc")).thenReturn("ENC(abc)");
        service = new SpeedrunAccountService(accounts, client, limiter, encryption, resolver, cache,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private SpeedrunAccount stored() {
        ArgumentCaptor<SpeedrunAccount> saved = ArgumentCaptor.forClass(SpeedrunAccount.class);
        verify(accounts).save(saved.capture());
        return saved.getValue();
    }

    private SpeedrunAccount existing() {
        SpeedrunAccount row = new SpeedrunAccount(user.getId());
        row.setUpdatedAt(NOW.minus(Duration.ofDays(3)));
        when(accounts.findById(user.getId())).thenReturn(Optional.of(row));
        return row;
    }

    private static void assertProblem(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiProblem.class,
                problem -> assertThat(problem.code()).isEqualTo(code));
    }

    @Test
    void keyIsStoredEncryptedAndNeverEchoed() {
        when(client.profile("abc")).thenReturn(Optional.of(KEY_OWNER));

        service.saveApiKey(user, "  abc ");

        SpeedrunAccount row = stored();
        assertThat(row.getUserId()).isEqualTo(user.getId());
        assertThat(row.getApiKeyEnc()).isEqualTo("ENC(abc)").isNotEqualTo("abc");
        assertThat(row.getKeyUserId()).isEqualTo("k1");
        assertThat(row.getKeyUserName()).isEqualTo("KeyOwner");
        assertThat(row.getKeyCheckedAt()).isEqualTo(NOW);
        assertThat(row.isKeyInvalid()).isFalse();
        assertThat(row.getUpdatedAt()).isEqualTo(NOW);
        assertThat(row.toString()).doesNotContain("ENC(abc)");

        when(accounts.findById(user.getId())).thenReturn(Optional.of(row));
        when(resolver.resolve(any(), any())).thenReturn(Optional.of(new SpeedrunIdentity("k1", "KeyOwner",
                "https://www.speedrun.com/users/KeyOwner", SpeedrunIdentity.Source.API_KEY)));
        SpeedrunData data = service.data(user);
        assertThat(data).isEqualTo(new SpeedrunData(null, true, false, "KeyOwner", "KeyOwner",
                "https://www.speedrun.com/users/KeyOwner", "api_key"));
        assertThat(data.toString()).doesNotContain("abc", "ENC(");
    }

    @Test
    void savingAKeyClearsAnEarlierRefusal() {
        SpeedrunAccount row = existing();
        row.setKeyInvalid(true);
        row.setDeclaredUsername("n1");
        when(client.profile("abc")).thenReturn(Optional.of(KEY_OWNER));

        service.saveApiKey(user, "abc");

        assertThat(stored()).isSameAs(row).satisfies(saved -> {
            assertThat(saved.isKeyInvalid()).isFalse();
            assertThat(saved.getDeclaredUsername()).isEqualTo("n1");
        });
    }

    @Test
    void refusedKeyIsNotSaved() {
        when(client.profile("abc")).thenReturn(Optional.empty());

        assertProblem(() -> service.saveApiKey(user, "abc"), ErrorCode.BAD_REQUEST);
        verify(accounts, never()).save(any());
        verify(encryption, never()).encrypt(anyString());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "abc def", "key/../x", "a-b"})
    void malformedKeyIsRefusedBeforeAnyCall(String key) {
        assertProblem(() -> service.saveApiKey(user, key), ErrorCode.BAD_REQUEST);
        verifyNoInteractions(client);
        verify(accounts, never()).save(any());
    }

    @Test
    void keyLongerThan64IsRefused() {
        assertProblem(() -> service.saveApiKey(user, "a".repeat(65)), ErrorCode.BAD_REQUEST);
        verifyNoInteractions(client);
    }

    @Test
    void unreachableSpeedrunIs503AndSavesNothing() {
        when(client.profile("abc")).thenThrow(new SpeedrunUnavailableException("down"));

        assertProblem(() -> service.saveApiKey(user, "abc"), ErrorCode.SERVICE_UNAVAILABLE);
        verify(accounts, never()).save(any());
    }

    @Test
    void limiterRefusalIs503() {
        when(limiter.member()).thenReturn(() -> false);

        assertProblem(() -> service.saveApiKey(user, "abc"), ErrorCode.SERVICE_UNAVAILABLE);
        assertProblem(() -> service.saveUsername(user, "n1"), ErrorCode.SERVICE_UNAVAILABLE);
        verifyNoInteractions(client);
    }

    @Test
    void unknownUsernameIs400() {
        when(client.user("Nobody")).thenReturn(Optional.empty());

        assertProblem(() -> service.saveUsername(user, "Nobody"), ErrorCode.BAD_REQUEST);
        verify(accounts, never()).save(any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "with space", "a/b", "0123456789012345678901234567890"})
    void malformedUsernameIsRefusedBeforeAnyCall(String name) {
        assertProblem(() -> service.saveUsername(user, name), ErrorCode.BAD_REQUEST);
        verifyNoInteractions(client);
    }

    @Test
    void unreachableSpeedrunOnUsernameIs503() {
        when(client.user("n1")).thenThrow(new SpeedrunUnavailableException("down"));

        assertProblem(() -> service.saveUsername(user, "n1"), ErrorCode.SERVICE_UNAVAILABLE);
        verify(accounts, never()).save(any());
    }

    @Test
    void knownUsernameIsSavedAndCached() {
        SpeedrunUser found = new SpeedrunUser("n1", "Declared", "https://www.speedrun.com/users/Declared", null);
        when(client.user("Declared")).thenReturn(Optional.of(found));

        service.saveUsername(user, " Declared ");

        SpeedrunAccount row = stored();
        assertThat(row.getDeclaredUsername()).isEqualTo("Declared");
        assertThat(row.getApiKeyEnc()).isNull();
        assertThat(row.getUpdatedAt()).isEqualTo(NOW);
        verify(cache).put(EnrichmentSource.SPEEDRUN_USER, "name:declared", found);
    }

    @Test
    void emptyRowIsDeleted() {
        SpeedrunAccount row = existing();
        row.setApiKeyEnc("enc");
        row.setKeyUserId("k1");
        row.setKeyUserName("KeyOwner");
        row.setKeyCheckedAt(NOW);
        row.setKeyInvalid(true);

        service.deleteApiKey(user);

        verify(accounts).delete(row);
        verify(accounts, never()).save(any());
    }

    @Test
    void deletingTheKeyKeepsTheName() {
        SpeedrunAccount row = existing();
        row.setDeclaredUsername("n1");
        row.setApiKeyEnc("enc");
        row.setKeyUserId("k1");
        row.setKeyUserName("KeyOwner");
        row.setKeyCheckedAt(NOW.minus(Duration.ofDays(1)));
        row.setKeyInvalid(true);

        service.deleteApiKey(user);

        assertThat(stored()).satisfies(saved -> {
            assertThat(saved.getDeclaredUsername()).isEqualTo("n1");
            assertThat(saved.getApiKeyEnc()).isNull();
            assertThat(saved.getKeyUserId()).isNull();
            assertThat(saved.getKeyUserName()).isNull();
            assertThat(saved.getKeyCheckedAt()).isNull();
            assertThat(saved.isKeyInvalid()).isFalse();
            assertThat(saved.getUpdatedAt()).isEqualTo(NOW);
        });
        verify(accounts, never()).delete(any());
    }

    @Test
    void deletingTheNameKeepsTheKeyThenTheLastOneDeletesTheRow() {
        SpeedrunAccount row = existing();
        row.setDeclaredUsername("n1");
        row.setApiKeyEnc("enc");

        service.deleteUsername(user);
        assertThat(stored().getDeclaredUsername()).isNull();

        row.setApiKeyEnc(null);
        row.setDeclaredUsername("n1");
        service.deleteUsername(user);
        verify(accounts).delete(row);
    }

    @Test
    void deletingWithoutARowDoesNothing() {
        service.deleteApiKey(user);
        service.deleteUsername(user);

        verify(accounts, never()).save(any());
        verify(accounts, never()).delete(any());
    }

    @Test
    void dataNeverCallsUpstream() {
        SpeedrunAccount row = existing();
        row.setDeclaredUsername("n1");
        ArgumentCaptor<FetchBudget> budget = ArgumentCaptor.forClass(FetchBudget.class);
        when(resolver.resolve(any(), budget.capture())).thenThrow(new UpstreamBudgetExceededException(Duration.ZERO));

        assertThat(service.data(user)).isEqualTo(new SpeedrunData("n1", false, false, null, null, null, null));
        assertThat(budget.getValue().tryAcquire()).isFalse();
    }

    @Test
    void dataWithoutRowIsEmptyButStillResolvesTwitch() {
        when(resolver.resolve(any(), any())).thenReturn(Optional.of(new SpeedrunIdentity("t1", "Streamer",
                "https://www.speedrun.com/users/Streamer", SpeedrunIdentity.Source.TWITCH)));

        assertThat(service.data(user)).isEqualTo(new SpeedrunData(null, false, false, null, "Streamer",
                "https://www.speedrun.com/users/Streamer", "twitch"));
    }
}
