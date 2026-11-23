package fr.enimaloc.catapult.service.retroachievements;

import fr.enimaloc.catapult.domain.retroachievements.RetroAchievementsApiKeyEntry;
import fr.enimaloc.catapult.getter.retroachievements.RetroAchievementsApiKeyRotator;
import fr.enimaloc.catapult.repository.retroachievements.RetroAchievementsApiKeyRepository;
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
class RetroAchievementsKeyPoolServiceTest {

    static final String RAW = "abcdef0123456789ABCDEF0123456789";

    @Mock RetroAchievementsApiKeyRepository repository;
    @Mock RetroAchievementsApiKeyRotator rotator;
    @Mock AdminEventPublisher events;

    RetroAchievementsKeyPoolService service;

    @BeforeEach
    void setUp() {
        service = new RetroAchievementsKeyPoolService(repository, Optional.of(rotator), events);
    }

    @Test
    void page_listsMaskedKeys_neverTheRawOne() {
        when(repository.findByExclusiveFalseWithOwner()).thenReturn(List.of(new RetroAchievementsApiKeyEntry(RAW)));
        when(rotator.getKeyBlockedUntil()).thenReturn(Map.of());

        var page = service.page();

        assertThat(page.retroachievementsEnabled()).isTrue();
        assertThat(page.keys()).singleElement().satisfies(k -> {
            assertThat(k.masked()).isEqualTo("abcd…6789");
            assertThat(k.id()).isEqualTo(ApiKeyHasher.id(RAW)).doesNotContain(RAW);
            assertThat(k.blocked()).isFalse();
        });
    }

    @Test
    void listMasksKeys() {
        when(repository.findByExclusiveFalseWithOwner()).thenReturn(List.of(new RetroAchievementsApiKeyEntry(RAW)));
        when(rotator.getKeyBlockedUntil()).thenReturn(Map.of());

        var masked = service.page().keys().getFirst().masked();

        assertThat(masked).isEqualTo("abcd…6789").contains("…").doesNotContain(RAW);
    }

    @Test
    void page_withoutRotator_isReportedDisabled() {
        var bare = new RetroAchievementsKeyPoolService(repository, Optional.empty(), events);
        when(repository.findByExclusiveFalseWithOwner()).thenReturn(List.of(new RetroAchievementsApiKeyEntry(RAW)));

        var page = bare.page();

        assertThat(page.retroachievementsEnabled()).isFalse();
        assertThat(page.keys()).hasSize(1);
    }

    @Test
    void add_persistsRefreshesAndAnnounces() {
        when(repository.existsById(RAW)).thenReturn(false);

        service.add(RAW);

        verify(repository).save(any(RetroAchievementsApiKeyEntry.class));
        verify(rotator).refreshKeys();
        verify(events).keyAdded(eq(AdminEventPublisher.PROVIDER_RETROACHIEVEMENTS), any());
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
        when(repository.findByExclusiveFalse()).thenReturn(List.of(new RetroAchievementsApiKeyEntry(RAW)));

        service.delete(ApiKeyHasher.id(RAW));

        verify(repository).deleteById(RAW);
        verify(rotator).refreshKeys();
        verify(events).keyDeleted(AdminEventPublisher.PROVIDER_RETROACHIEVEMENTS, ApiKeyHasher.id(RAW));
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
        verify(events).keysRefreshed(eq(AdminEventPublisher.PROVIDER_RETROACHIEVEMENTS), anyList());
    }
}
