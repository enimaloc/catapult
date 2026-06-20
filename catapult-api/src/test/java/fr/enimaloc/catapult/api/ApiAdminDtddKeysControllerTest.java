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
        when(repository.existsById("abcdef1234567890ABCDEF")).thenReturn(false);
        controller.add(new ApiAdminDtddKeysController.AddKeyRequest("abcdef1234567890ABCDEF"));
        verify(repository).save(any(DtddApiKeyEntry.class));
        verify(rotator).refreshKeys();
    }

    @Test
    void page_returnsMaskedStatuses() {
        when(repository.findByExclusiveFalseWithOwner()).thenReturn(List.of(
            new DtddApiKeyEntry("ABCDEFGHIJKLMNOP")
        ));
        when(rotator.getKeyBlockedUntil()).thenReturn(Map.of());
        var page = controller.page();
        assertThat(page.dtddEnabled()).isTrue();
        assertThat(page.keyStatuses()).containsKey("ABCDEFGHIJKLMNOP");
        assertThat(page.keyStatuses().get("ABCDEFGHIJKLMNOP").masked()).contains("…");
    }

    @Test
    void delete_removesAndRefreshes() {
        controller.delete(new ApiAdminDtddKeysController.DeleteKeyRequest("KEY_X"));
        verify(repository).deleteById("KEY_X");
        verify(rotator).refreshKeys();
    }
}
