package fr.enimaloc.catapult.service.osu;

import fr.enimaloc.catapult.api.admin.ApiAdminOsuCredentialsController;
import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.admin.OsuCredentialsData;
import fr.enimaloc.catapult.common.dto.admin.OsuCredentialsRequest;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.osu.OsuAppCredentials;
import fr.enimaloc.catapult.repository.osu.OsuAppCredentialsRepository;
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

class OsuCredentialsServiceTest {

    static final String SECRET = "Xvq3h0hYZHJ6vq3R6HeLhVHc0l3oVpAbCdEfGhIj";

    private final TokenEncryptionService encryption = new TokenEncryptionService("0123456789abcdef0123456789abcdef");
    private final OsuAppCredentialsRepository repository = mock(OsuAppCredentialsRepository.class);
    private final AtomicReference<OsuAppCredentials> stored = new AtomicReference<>();
    private final MutableClock clock = new MutableClock();
    private OsuCredentialsService service;

    @BeforeEach
    void setUp() {
        when(repository.findById(OsuAppCredentials.ID)).thenAnswer(i -> Optional.ofNullable(stored.get()));
        when(repository.save(any())).thenAnswer(i -> {
            stored.set(i.getArgument(0));
            return i.getArgument(0);
        });
        doAnswer(i -> {
            stored.set(null);
            return null;
        }).when(repository).deleteAllInBatch();
        service = new OsuCredentialsService(repository, encryption, clock);
    }

    @Test
    void nothingSet_isOff() {
        assertThat(service.current()).isEmpty();
        assertThat(service.available()).isFalse();
        assertThat(service.page()).isEqualTo(OsuCredentialsData.none());
    }

    @Test
    void save_encryptsTheSecret_andThePageNeverShowsIt() {
        service.save(" 12345 ", " " + SECRET + " ");

        OsuAppCredentials row = stored.get();
        assertThat(row.getClientId()).isEqualTo("12345");
        assertThat(row.getClientSecretEnc()).isNotEqualTo(SECRET).doesNotContain(SECRET);
        assertThat(row.toString()).doesNotContain(row.getClientSecretEnc());
        assertThat(service.current()).contains(new OsuCredentialsService.Credentials("12345", SECRET));
        assertThat(service.current().orElseThrow().toString()).doesNotContain(SECRET);

        OsuCredentialsData page = service.page();
        assertThat(page.configured()).isTrue();
        assertThat(page.clientId()).isEqualTo("12345");
        assertThat(page.toString()).doesNotContain(SECRET);
    }

    @Test
    void aBlankSecret_keepsTheOneSet_butIsNeededTheFirstTimeAndForAnotherClient() {
        assertThatThrownBy(() -> service.save("12345", ""))
                .isInstanceOfSatisfying(ApiProblem.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.BAD_REQUEST));
        verify(repository, never()).save(any());

        service.save("12345", SECRET);
        service.save("12345", " ");
        assertThat(service.current()).contains(new OsuCredentialsService.Credentials("12345", SECRET));

        assertThatThrownBy(() -> service.save("67890", null))
                .isInstanceOfSatisfying(ApiProblem.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.BAD_REQUEST));
        assertThat(service.current()).contains(new OsuCredentialsService.Credentials("12345", SECRET));
    }

    @ParameterizedTest
    @CsvSource({"abc," + SECRET, "12345,short", "12345,has spaces in the secret value"})
    void aMalformedValue_isA400(String id, String secret) {
        assertThatThrownBy(() -> service.save(id, secret))
                .isInstanceOfSatisfying(ApiProblem.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.BAD_REQUEST));
        verify(repository, never()).save(any());
    }

    @Test
    void aCrossedWrite_isRedoneOnce_thenIsA409() {
        when(repository.save(any())).thenThrow(new OptimisticLockingFailureException("crossed"));

        assertThatThrownBy(() -> service.save("12345", SECRET))
                .isInstanceOfSatisfying(ApiProblem.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
        verify(repository, times(2)).save(any());
    }

    @Test
    void theCredentials_areReadAgainAfterTheMemo_soAnotherInstancesChangeIsSeen() {
        service.save("12345", SECRET);
        assertThat(service.available()).isTrue();

        stored.set(null); // removed by another instance
        assertThat(service.available()).isTrue();
        clock.advance(OsuCredentialsService.MEMO.plusSeconds(1));
        assertThat(service.available()).isFalse();
    }

    @Test
    void delete_turnsTheSourceOff_atOnce() {
        service.save("12345", SECRET);

        service.delete();

        assertThat(service.available()).isFalse();
    }

    @Test
    void undecryptableCredentials_areOff() {
        OsuAppCredentials row = new OsuAppCredentials();
        row.setClientId("12345");
        row.setClientSecretEnc("garbage");
        row.setUpdatedAt(Instant.EPOCH);
        stored.set(row);

        assertThat(service.current()).isEmpty();
        assertThat(service.page().configured()).isTrue();
    }

    @Test
    void theAdminController_relaysToTheService() {
        ApiAdminOsuCredentialsController controller = new ApiAdminOsuCredentialsController(service);

        controller.save(new OsuCredentialsRequest("12345", SECRET));
        assertThat(controller.page().configured()).isTrue();
        assertThat(new OsuCredentialsRequest("12345", SECRET).toString()).doesNotContain(SECRET);

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
