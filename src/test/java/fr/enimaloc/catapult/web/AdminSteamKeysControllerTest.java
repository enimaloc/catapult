package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.SteamApiKeyEntry;
import fr.enimaloc.catapult.getter.SteamApiKeyRotator;
import fr.enimaloc.catapult.repository.SteamApiKeyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminSteamKeysControllerTest {

    @Mock SteamApiKeyRepository repository;
    @Mock SteamApiKeyRotator rotator;
    @InjectMocks AdminSteamKeysController controller;

    @Test
    void page_populatesKeyStatuses_andSteamEnabled() {
        SteamApiKeyEntry entry = new SteamApiKeyEntry("ABCD1234ABCD1234ABCD1234ABCD1234");
        when(repository.findByExclusiveFalseWithOwner()).thenReturn(List.of(entry));
        when(rotator.getKeyBlockedUntil()).thenReturn(Map.of());

        Model model = new ExtendedModelMap();
        String view = controller.page(model);

        assertThat(view).isEqualTo("admin/steam-keys");
        assertThat(model.getAttribute("keyStatuses")).isNotNull();
        assertThat(model.getAttribute("steamEnabled")).isEqualTo(true);
    }

    @Test
    void page_steamEnabled_falseWhenRotatorNull() {
        controller = new AdminSteamKeysController(repository);
        when(repository.findByExclusiveFalseWithOwner()).thenReturn(List.of());

        Model model = new ExtendedModelMap();
        controller.page(model);

        assertThat(model.getAttribute("steamEnabled")).isEqualTo(false);
    }

    @Test
    void page_blockedKey_showsRemainingSeconds() {
        SteamApiKeyEntry entry = new SteamApiKeyEntry("ABCD1234ABCD1234ABCD1234ABCD1234");
        long blockedUntil = System.currentTimeMillis() + 60_000L;
        when(repository.findByExclusiveFalseWithOwner()).thenReturn(List.of(entry));
        when(rotator.getKeyBlockedUntil()).thenReturn(Map.of("ABCD1234ABCD1234ABCD1234ABCD1234", blockedUntil));

        Model model = new ExtendedModelMap();
        controller.page(model);

        @SuppressWarnings("unchecked")
        var statuses = (java.util.Map<String, AdminSteamKeysController.KeyStatus>) model.getAttribute("keyStatuses");
        assertThat(statuses).isNotNull();
        AdminSteamKeysController.KeyStatus status = statuses.get("ABCD1234ABCD1234ABCD1234ABCD1234");
        assertThat(status.blocked()).isTrue();
        assertThat(status.blockedForSeconds()).isGreaterThan(0);
    }

    @Test
    void add_savesNewKey_andRefreshesRotator() {
        when(repository.existsById("ABCD1234ABCD1234ABCD1234ABCD1234")).thenReturn(false);

        String view = controller.add("  ABCD1234ABCD1234ABCD1234ABCD1234  ");

        verify(repository).save(argThat(e -> e.getApiKey().equals("ABCD1234ABCD1234ABCD1234ABCD1234")));
        verify(rotator).refreshKeys();
        assertThat(view).isEqualTo("redirect:/admin/steam-keys");
    }

    @Test
    void add_rejectsInvalidKeyFormat() {
        String view = controller.add("NOT_A_VALID_KEY");
        verify(repository, never()).save(any());
        verify(rotator, never()).refreshKeys();
        assertThat(view).isEqualTo("redirect:/admin/steam-keys?error=invalid");
    }

    @Test
    void add_skipsAlreadyPresentKey() {
        when(repository.existsById("ABCD1234ABCD1234ABCD1234ABCD1234")).thenReturn(true);

        controller.add("ABCD1234ABCD1234ABCD1234ABCD1234");

        verify(repository, never()).save(any());
        verify(rotator, never()).refreshKeys();
    }

    @Test
    void delete_removesKey_andRefreshesRotator() {
        String view = controller.delete("OLD_KEY");

        verify(repository).deleteById("OLD_KEY");
        verify(rotator).refreshKeys();
        assertThat(view).isEqualTo("redirect:/admin/steam-keys");
    }

    @Test
    void refresh_callsRotatorRefresh() {
        String view = controller.refresh();

        verify(rotator).refreshKeys();
        assertThat(view).isEqualTo("redirect:/admin/steam-keys");
    }
}
