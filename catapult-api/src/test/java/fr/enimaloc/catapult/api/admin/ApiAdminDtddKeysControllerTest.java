package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.common.dto.admin.AddKeyRequest;
import fr.enimaloc.catapult.domain.dtdd.DtddApiKeyEntry;
import fr.enimaloc.catapult.getter.dtdd.DtddApiKeyRotator;
import fr.enimaloc.catapult.repository.dtdd.DtddApiKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import fr.enimaloc.catapult.service.notification.AdminEventPublisher;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiAdminDtddKeysControllerTest {

    @Mock DtddApiKeyRepository repository;
    @Mock DtddApiKeyRotator rotator;
    @Mock fr.enimaloc.catapult.service.notification.AdminEventPublisher events;

    ApiAdminDtddKeysController controller;

    @BeforeEach
    void setUp() {
        controller = new ApiAdminDtddKeysController(repository, Optional.of(rotator), events);
    }

    @Test
    void add_rejectsInvalidFormat() {
        assertThatThrownBy(() -> controller.add(new AddKeyRequest("bad!chars$")))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void add_persistsAndRefreshes() {
        when(repository.existsById("ddd_abcdef1234567890ABCD")).thenReturn(false);
        controller.add(new AddKeyRequest("ddd_abcdef1234567890ABCD"));
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
        var status = page.keys().getFirst();
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

        controller.delete(id);

        verify(repository).deleteById("ABCDEFGHIJKLMNOP");
        verify(rotator).refreshKeys();
    }

    @Test
    void page_withoutRotator_dtddIsReportedDisabled_andNothingIsBlocked() {
        var bare = new ApiAdminDtddKeysController(repository, Optional.empty(), events);
        when(repository.findByExclusiveFalseWithOwner()).thenReturn(List.of(
            new DtddApiKeyEntry("ABCDEFGHIJKLMNOP")
        ));

        var page = bare.page();

        assertThat(page.dtddEnabled()).isFalse();
        assertThat(page.keys()).hasSize(1);
        assertThat(page.keys().getFirst().blocked()).isFalse();
    }

    @Test
    void editsWithoutRotator_stillWork_andAreAnnounced() {
        var bare = new ApiAdminDtddKeysController(repository, Optional.empty(), events);
        when(repository.existsById("ddd_abcdef1234567890ABCD")).thenReturn(false);
        when(repository.findByExclusiveFalse()).thenReturn(List.of(new DtddApiKeyEntry("ABCDEFGHIJKLMNOP")));
        when(repository.findByExclusiveFalseWithOwner()).thenReturn(List.of());

        bare.add(new AddKeyRequest("ddd_abcdef1234567890ABCD"));
        bare.delete(ApiKeyHasher.id("ABCDEFGHIJKLMNOP"));
        bare.refresh();

        verify(repository).save(any(DtddApiKeyEntry.class));
        verify(repository).deleteById("ABCDEFGHIJKLMNOP");
        verify(events).keyAdded(eq(AdminEventPublisher.PROVIDER_DTDD), any());
        verify(events).keyDeleted(AdminEventPublisher.PROVIDER_DTDD, ApiKeyHasher.id("ABCDEFGHIJKLMNOP"));
        verify(events).keysRefreshed(eq(AdminEventPublisher.PROVIDER_DTDD), anyList());
    }

    @Test
    void delete_unknownKeyId_throwsNotFound() {
        when(repository.findByExclusiveFalse()).thenReturn(List.of());
        assertThatThrownBy(() ->
                controller.delete("0000000000000000"))
            .isInstanceOf(ResponseStatusException.class);
        verify(repository, never()).deleteById(any());
    }
}
