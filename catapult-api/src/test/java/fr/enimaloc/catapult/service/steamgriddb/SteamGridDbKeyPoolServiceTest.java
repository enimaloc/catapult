package fr.enimaloc.catapult.service.steamgriddb;

import fr.enimaloc.catapult.domain.steamgriddb.SteamGridDbApiKeyEntry;
import fr.enimaloc.catapult.getter.steamgriddb.SteamGridDbApiKeyRotator;
import fr.enimaloc.catapult.repository.steamgriddb.SteamGridDbApiKeyRepository;
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
class SteamGridDbKeyPoolServiceTest {

    static final String RAW = "0123456789abcdef0123456789ABCDEF";

    @Mock SteamGridDbApiKeyRepository repository;
    @Mock SteamGridDbApiKeyRotator rotator;
    @Mock AdminEventPublisher events;

    SteamGridDbKeyPoolService service;

    @BeforeEach
    void setUp() {
        service = new SteamGridDbKeyPoolService(repository, Optional.of(rotator), events);
    }

    @Test
    void page_listsMaskedKeys_neverTheRawOne() {
        when(repository.findByExclusiveFalseWithOwner()).thenReturn(List.of(new SteamGridDbApiKeyEntry(RAW)));
        when(rotator.getKeyBlockedUntil()).thenReturn(Map.of());

        var page = service.page();

        assertThat(page.steamgriddbEnabled()).isTrue();
        assertThat(page.keys()).singleElement().satisfies(k -> {
            assertThat(k.masked()).isEqualTo("0123…CDEF");
            assertThat(k.id()).isEqualTo(ApiKeyHasher.id(RAW)).doesNotContain(RAW);
            assertThat(k.blocked()).isFalse();
        });
    }

    @Test
    void page_withoutRotator_isReportedDisabled() {
        var bare = new SteamGridDbKeyPoolService(repository, Optional.empty(), events);
        when(repository.findByExclusiveFalseWithOwner()).thenReturn(List.of(new SteamGridDbApiKeyEntry(RAW)));

        var page = bare.page();

        assertThat(page.steamgriddbEnabled()).isFalse();
        assertThat(page.keys()).hasSize(1);
    }

    @Test
    void add_persistsRefreshesAndAnnounces() {
        when(repository.existsById(RAW)).thenReturn(false);

        service.add(RAW);

        verify(repository).save(any(SteamGridDbApiKeyEntry.class));
        verify(rotator).refreshKeys();
        verify(events).keyAdded(eq(AdminEventPublisher.PROVIDER_STEAMGRIDDB), any());
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
        when(repository.findByExclusiveFalse()).thenReturn(List.of(new SteamGridDbApiKeyEntry(RAW)));

        service.delete(ApiKeyHasher.id(RAW));

        verify(repository).deleteById(RAW);
        verify(rotator).refreshKeys();
        verify(events).keyDeleted(AdminEventPublisher.PROVIDER_STEAMGRIDDB, ApiKeyHasher.id(RAW));
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
        verify(events).keysRefreshed(eq(AdminEventPublisher.PROVIDER_STEAMGRIDDB), anyList());
    }
}
