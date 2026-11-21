package fr.enimaloc.catapult.service.opencritic;

import fr.enimaloc.catapult.domain.opencritic.OpenCriticApiKeyEntry;
import fr.enimaloc.catapult.getter.opencritic.OpenCriticApiKeyRotator;
import fr.enimaloc.catapult.repository.opencritic.OpenCriticApiKeyRepository;
import fr.enimaloc.catapult.service.ApiKeyHasher;
import fr.enimaloc.catapult.service.notification.AdminEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
class OpenCriticKeyPoolServiceTest {

    static final String RAW = "AbCdEf0123456789AbCdEf0123456789AbCdEf0123";

    @Mock OpenCriticApiKeyRepository repository;
    @Mock OpenCriticApiKeyRotator rotator;
    @Mock AdminEventPublisher events;

    OpenCriticKeyPoolService service;

    @BeforeEach
    void setUp() {
        service = new OpenCriticKeyPoolService(repository, Optional.of(rotator), events);
    }

    @Test
    void page_listsMaskedKeys_neverTheRawOne() {
        when(repository.findByExclusiveFalseWithOwner()).thenReturn(List.of(new OpenCriticApiKeyEntry(RAW)));
        when(rotator.getKeyBlockedUntil()).thenReturn(Map.of());

        var page = service.page();

        assertThat(page.opencriticEnabled()).isTrue();
        assertThat(page.keys()).singleElement().satisfies(k -> {
            assertThat(k.masked()).isEqualTo("AbCd…0123");
            assertThat(k.id()).isEqualTo(ApiKeyHasher.id(RAW)).doesNotContain(RAW);
            assertThat(k.blocked()).isFalse();
        });
    }

    @Test
    void page_withoutRotator_isReportedDisabled() {
        var bare = new OpenCriticKeyPoolService(repository, Optional.empty(), events);
        when(repository.findByExclusiveFalseWithOwner()).thenReturn(List.of(new OpenCriticApiKeyEntry(RAW)));

        var page = bare.page();

        assertThat(page.opencriticEnabled()).isFalse();
        assertThat(page.keys()).hasSize(1);
    }

    @Test
    void add_persistsRefreshesAndAnnounces() {
        when(repository.existsById(RAW)).thenReturn(false);

        service.add(RAW);

        verify(repository).save(any(OpenCriticApiKeyEntry.class));
        verify(rotator).refreshKeys();
        verify(events).keyAdded(eq(AdminEventPublisher.PROVIDER_OPENCRITIC), any());
    }

    @Test
    void add_aKeyAlreadyInThePool_changesNothing() {
        when(repository.existsById(RAW)).thenReturn(true);

        service.add(RAW);

        verify(repository, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void delete_resolvesTheHashedIdAndDeletes() {
        when(repository.findByExclusiveFalse()).thenReturn(List.of(new OpenCriticApiKeyEntry(RAW)));

        service.delete(ApiKeyHasher.id(RAW));

        verify(repository).deleteById(RAW);
        verify(rotator).refreshKeys();
        verify(events).keyDeleted(AdminEventPublisher.PROVIDER_OPENCRITIC, ApiKeyHasher.id(RAW));
    }

    @Test
    void delete_unknownId_isNotFound() {
        when(repository.findByExclusiveFalse()).thenReturn(List.of());

        assertThatThrownBy(() -> service.delete("0000000000000000")).isInstanceOf(ResponseStatusException.class);
        verify(repository, never()).deleteById(any());
    }

    @Test
    void refresh_reloadsTheRotatorAndAnnouncesTheList() {
        when(repository.findByExclusiveFalseWithOwner()).thenReturn(List.of());
        when(rotator.getKeyBlockedUntil()).thenReturn(Map.of());

        service.refresh();

        verify(rotator).refreshKeys();
        verify(events).keysRefreshed(eq(AdminEventPublisher.PROVIDER_OPENCRITIC), anyList());
    }
}
