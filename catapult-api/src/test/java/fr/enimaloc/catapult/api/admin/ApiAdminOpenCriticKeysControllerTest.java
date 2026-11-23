package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.common.dto.admin.AddKeyRequest;
import fr.enimaloc.catapult.domain.opencritic.OpenCriticApiKeyEntry;
import fr.enimaloc.catapult.repository.opencritic.OpenCriticApiKeyRepository;
import fr.enimaloc.catapult.service.ApiKeyHasher;
import fr.enimaloc.catapult.service.notification.AdminEventPublisher;
import fr.enimaloc.catapult.service.opencritic.OpenCriticKeyPoolService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiAdminOpenCriticKeysControllerTest {

    static final String RAW = "AbCdEf0123456789AbCdEf0123456789AbCdEf0123";

    @Mock OpenCriticApiKeyRepository repository;
    @Mock AdminEventPublisher events;

    ApiAdminOpenCriticKeysController controller;

    @BeforeEach
    void setUp() {
        controller = new ApiAdminOpenCriticKeysController(
                new OpenCriticKeyPoolService(repository, Optional.empty(), events));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad!chars$", "AbCdEf0123456789AbCdEf0123456789AbCdEf0", "AbCdEf0123456789AbCdEf0123456789AbCdEf0123456789AbCdEf0123456789",
            "AbCdEf0123456789AbCdEf0123456789AbCdEf01_3", "AbCdEf0123456789AbCdEf0123456789AbCdEf01 3"})
    void add_rejectsAnInvalidFormat_with400(String key) {
        assertThatThrownBy(() -> controller.add(new AddKeyRequest(key)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(repository, never()).save(any());
    }

    @Test
    void add_acceptsFortyToSixtyAlphanumericCharactersTrimmed() {
        when(repository.existsById(RAW)).thenReturn(false);

        controller.add(new AddKeyRequest("  " + RAW + " "));

        verify(repository).save(any(OpenCriticApiKeyEntry.class));
    }

    @Test
    void page_isMasked_andDisabledWithoutRotator() {
        when(repository.findByExclusiveFalseWithOwner()).thenReturn(List.of(new OpenCriticApiKeyEntry(RAW)));

        var page = controller.page();

        assertThat(page.opencriticEnabled()).isFalse();
        assertThat(page.keys().getFirst().masked()).contains("…").doesNotContain(RAW);
    }

    @Test
    void delete_byHashedId() {
        when(repository.findByExclusiveFalse()).thenReturn(List.of(new OpenCriticApiKeyEntry(RAW)));

        controller.delete(ApiKeyHasher.id(RAW));

        verify(repository).deleteById(RAW);
    }

    @Test
    void refresh_isAnnounced() {
        when(repository.findByExclusiveFalseWithOwner()).thenReturn(List.of());

        controller.refresh();

        verify(events).keysRefreshed(eq(AdminEventPublisher.PROVIDER_OPENCRITIC), anyList());
    }
}
