package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.common.dto.admin.AddKeyRequest;
import fr.enimaloc.catapult.domain.retroachievements.RetroAchievementsApiKeyEntry;
import fr.enimaloc.catapult.repository.retroachievements.RetroAchievementsApiKeyRepository;
import fr.enimaloc.catapult.service.ApiKeyHasher;
import fr.enimaloc.catapult.service.notification.AdminEventPublisher;
import fr.enimaloc.catapult.service.retroachievements.RetroAchievementsKeyPoolService;
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
class ApiAdminRetroAchievementsKeysControllerTest {

    static final String RAW = "abcdef0123456789ABCDEF0123456789";

    @Mock RetroAchievementsApiKeyRepository repository;
    @Mock AdminEventPublisher events;

    ApiAdminRetroAchievementsKeysController controller;

    @BeforeEach
    void setUp() {
        controller = new ApiAdminRetroAchievementsKeysController(
                new RetroAchievementsKeyPoolService(repository, Optional.empty(), events));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad!chars$", "short", "0123456789abcdef0123456789ABCDEF01234567890123456789012345678901234567890",
            "abcdef0123456789 ABCDEF0123456789"})
    void add_rejectsAnInvalidFormat_with400(String key) {
        assertThatThrownBy(() -> controller.add(new AddKeyRequest(key)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(repository, never()).save(any());
    }

    @Test
    void add_rejectsAMissingKey_with400() {
        assertThatThrownBy(() -> controller.add(new AddKeyRequest(null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(repository, never()).save(any());
    }

    @Test
    void add_acceptsAnAlphanumericKeyTrimmed() {
        when(repository.existsById(RAW)).thenReturn(false);

        controller.add(new AddKeyRequest("  " + RAW + " "));

        verify(repository).save(any(RetroAchievementsApiKeyEntry.class));
    }

    @Test
    void page_isMasked_andDisabledWithoutRotator() {
        when(repository.findByExclusiveFalseWithOwner()).thenReturn(List.of(new RetroAchievementsApiKeyEntry(RAW)));

        var page = controller.page();

        assertThat(page.retroachievementsEnabled()).isFalse();
        assertThat(page.keys().getFirst().masked()).contains("…").doesNotContain(RAW);
    }

    @Test
    void delete_byHashedId() {
        when(repository.findByExclusiveFalse()).thenReturn(List.of(new RetroAchievementsApiKeyEntry(RAW)));

        controller.delete(ApiKeyHasher.id(RAW));

        verify(repository).deleteById(RAW);
    }

    @Test
    void refresh_isAnnounced() {
        when(repository.findByExclusiveFalseWithOwner()).thenReturn(List.of());

        controller.refresh();

        verify(events).keysRefreshed(eq(AdminEventPublisher.PROVIDER_RETROACHIEVEMENTS), anyList());
    }
}
