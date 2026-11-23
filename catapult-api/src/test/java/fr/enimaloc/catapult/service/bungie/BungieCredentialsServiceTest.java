package fr.enimaloc.catapult.service.bungie;

import fr.enimaloc.catapult.api.admin.ApiAdminBungieCredentialsController;
import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.admin.BungieCredentialsData;
import fr.enimaloc.catapult.common.dto.admin.BungieCredentialsRequest;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.bungie.BungieAppCredentials;
import fr.enimaloc.catapult.repository.bungie.BungieAppCredentialsRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BungieCredentialsServiceTest {

    static final String KEY = "0123456789abcdef0123456789ABCDEF";
    static final String SECRET = "Xvq3h0hYZ.HJ6vq3R6HeLhVHc0l3oVp";

    private final TokenEncryptionService encryption = new TokenEncryptionService("0123456789abcdef0123456789abcdef");
    private final BungieAppCredentialsRepository repository = mock(BungieAppCredentialsRepository.class);
    private final AtomicReference<BungieAppCredentials> stored = new AtomicReference<>();
    private final MutableClock clock = new MutableClock();
    private BungieCredentialsService service;

    @BeforeEach
    void setUp() {
        when(repository.findById(BungieAppCredentials.ID)).thenAnswer(i -> Optional.ofNullable(stored.get()));
        when(repository.save(any())).thenAnswer(i -> {
            stored.set(i.getArgument(0));
            return i.getArgument(0);
        });
        doAnswer(i -> {
            stored.set(null);
            return null;
        }).when(repository).deleteAllInBatch();
        service = new BungieCredentialsService(repository, encryption, clock);
    }

    @Test
    void nothingSet_isOff() {
        assertThat(service.current()).isEmpty();
        assertThat(service.available()).isFalse();
        assertThat(service.page()).isEqualTo(BungieCredentialsData.none());
    }

    @Test
    void save_encryptsTheSecrets_andThePageNeverShowsThem() {
        service.save(" 12345 ", " " + SECRET + " ", KEY);

        BungieAppCredentials row = stored.get();
        assertThat(row.getClientId()).isEqualTo("12345");
        assertThat(row.getClientSecretEnc()).isNotEqualTo(SECRET).doesNotContain(SECRET);
        assertThat(row.getApiKeyEnc()).isNotEqualTo(KEY).doesNotContain(KEY);
        assertThat(row.toString()).doesNotContain(row.getClientSecretEnc()).doesNotContain(row.getApiKeyEnc());
        assertThat(service.current()).contains(new BungieCredentialsService.Credentials("12345", SECRET, KEY));
        assertThat(service.current().orElseThrow().toString()).doesNotContain(SECRET).doesNotContain(KEY);

        BungieCredentialsData page = service.page();
        assertThat(page.configured()).isTrue();
        assertThat(page.clientId()).isEqualTo("12345");
        assertThat(page.apiKeyMasked()).isEqualTo("0123…CDEF");
        assertThat(page.toString()).doesNotContain(SECRET).doesNotContain(KEY);
    }

    @Test
    void aBlankSecret_keepsTheOneSet_butIsNeededTheFirstTime() {
        assertThatThrownBy(() -> service.save("12345", "", KEY))
                .isInstanceOfSatisfying(ApiProblem.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.BAD_REQUEST));
        verify(repository, never()).save(any());

        service.save("12345", SECRET, KEY);
        service.save("12345", " ", null);
        assertThat(service.current()).contains(new BungieCredentialsService.Credentials("12345", SECRET, KEY));

        // another application needs its own secret and key
        assertThatThrownBy(() -> service.save("67890", " ", null))
                .isInstanceOfSatisfying(ApiProblem.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.BAD_REQUEST));
        assertThatThrownBy(() -> service.save("67890", SECRET, null)).isInstanceOf(ApiProblem.class);
    }

    @ParameterizedTest
    @CsvSource({"abc," + SECRET + "," + KEY, "12345,short," + KEY, "12345," + SECRET + ",nothex",
            "12345," + SECRET + ",0123456789abcdef0123456789abcdef0"})
    void aMalformedValue_isA400(String id, String secret, String key) {
        assertThatThrownBy(() -> service.save(id, secret, key))
                .isInstanceOfSatisfying(ApiProblem.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.BAD_REQUEST));
        verify(repository, never()).save(any());
    }

    @Test
    void aCrossedWrite_isRedoneOnce_thenIsA409() {
        when(repository.save(any())).thenThrow(new OptimisticLockingFailureException("crossed"));

        assertThatThrownBy(() -> service.save("12345", SECRET, KEY))
                .isInstanceOfSatisfying(ApiProblem.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
        verify(repository, times(2)).save(any());
    }

    @Test
    void theCredentials_areReadAgainAfterTheMemo_soAnotherInstancesChangeIsSeen() {
        service.save("12345", SECRET, KEY);
        assertThat(service.available()).isTrue();

        stored.set(null); // removed by another instance
        assertThat(service.available()).isTrue();
        clock.advance(BungieCredentialsService.MEMO.plusSeconds(1));
        assertThat(service.available()).isFalse();
    }

    @Test
    void delete_turnsTheSourceOff_atOnce() {
        service.save("12345", SECRET, KEY);
        assertThat(service.available()).isTrue();

        service.delete();

        assertThat(service.available()).isFalse();
    }

    @Test
    void undecryptableCredentials_areOff_withoutTheValueInTheLog() {
        BungieAppCredentials row = new BungieAppCredentials();
        row.setClientId("12345");
        row.setClientSecretEnc("garbage");
        row.setApiKeyEnc("garbage");
        row.setUpdatedAt(Instant.EPOCH);
        stored.set(row);

        assertThat(service.current()).isEmpty();
        assertThat(service.page().apiKeyMasked()).isEqualTo("…");
    }

    @Test
    void theAdminController_relaysToTheService() {
        ApiAdminBungieCredentialsController controller = new ApiAdminBungieCredentialsController(service);

        controller.save(new BungieCredentialsRequest("12345", SECRET, KEY));
        assertThat(controller.page().configured()).isTrue();
        assertThat(new BungieCredentialsRequest("12345", SECRET, KEY).toString())
                .doesNotContain(SECRET).doesNotContain(KEY);

        controller.delete();
        assertThat(controller.page().configured()).isFalse();
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-23T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
