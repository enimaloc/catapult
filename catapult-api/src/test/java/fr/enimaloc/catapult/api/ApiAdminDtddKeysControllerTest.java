package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.DtddApiKeyEntry;
import fr.enimaloc.catapult.getter.DtddApiKeyRotator;
import fr.enimaloc.catapult.repository.DtddApiKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiAdminDtddKeysControllerTest {

    @Mock DtddApiKeyRepository repository;
    @Mock DtddApiKeyRotator rotator;

    ApiAdminDtddKeysController controller;

    @BeforeEach
    void setUp() {
        controller = new ApiAdminDtddKeysController(repository, rotator);
    }

    @Test
    void add_rejectsInvalidFormat() {
        assertThatThrownBy(() -> controller.add(new ApiAdminDtddKeysController.AddKeyRequest("bad!chars$")))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void add_persistsAndRefreshes() {
        when(repository.existsById("ddd_abcdef1234567890ABCD")).thenReturn(false);
        controller.add(new ApiAdminDtddKeysController.AddKeyRequest("ddd_abcdef1234567890ABCD"));
        verify(repository).save(any(DtddApiKeyEntry.class));
        verify(rotator).refreshKeys();
    }

    @Test
    void page_returnsMaskedStatuses_withoutLeakingRawKey() {
        when(repository.findByExclusiveFalseWithOwner()).thenReturn(List.of(
            new DtddApiKeyEntry("ABCDEFGHIJKLMNOP")
        ));
        when(rotator.getKeyBlockedUntil()).thenReturn(Map.of());
        var page = controller.page();
        assertThat(page.dtddEnabled()).isTrue();
        assertThat(page.keys()).hasSize(1);
        var status = page.keys().get(0);
        assertThat(status.masked()).contains("…");
        assertThat(status.id()).hasSize(16); // 64-bit hex prefix
        assertThat(status.id()).doesNotContain("ABCDEFGHIJKLMNOP");
    }

    @Test
    void delete_resolvesKeyIdToRawAndDeletes() {
        when(repository.findByExclusiveFalse()).thenReturn(List.of(
            new DtddApiKeyEntry("ABCDEFGHIJKLMNOP")
        ));
        String id = ApiKeyHasher.id("ABCDEFGHIJKLMNOP");

        controller.delete(new ApiAdminDtddKeysController.DeleteKeyRequest(id));

        verify(repository).deleteById("ABCDEFGHIJKLMNOP");
        verify(rotator).refreshKeys();
    }

    @Test
    void delete_unknownKeyId_throwsNotFound() {
        when(repository.findByExclusiveFalse()).thenReturn(List.of());
        assertThatThrownBy(() ->
                controller.delete(new ApiAdminDtddKeysController.DeleteKeyRequest("0000000000000000")))
            .isInstanceOf(ResponseStatusException.class);
        verify(repository, never()).deleteById(any());
    }
}
