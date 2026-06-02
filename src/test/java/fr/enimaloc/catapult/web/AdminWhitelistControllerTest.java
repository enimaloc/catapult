package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.WhitelistEntry;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.WhitelistService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminWhitelistControllerTest {

    @Mock WhitelistService whitelistService;
    @Mock UserAccountRepository userAccountRepository;
    @InjectMocks AdminWhitelistController controller;

    @Test
    void page_populatesEntriesAndEnabledFlag() {
        WhitelistEntry entry = new WhitelistEntry("123");
        when(whitelistService.findAll()).thenReturn(List.of(entry));
        when(whitelistService.isEnabled()).thenReturn(true);
        when(userAccountRepository.findByTwitchId("123")).thenReturn(Optional.empty());

        Model model = new ExtendedModelMap();
        String view = controller.page(model);

        assertThat(view).isEqualTo("admin/whitelist");
        assertThat(model.getAttribute("entries")).isEqualTo(List.of(entry));
        assertThat(model.getAttribute("whitelistEnabled")).isEqualTo(true);
    }

    @Test
    void page_resolvesUsernameWhenAccountExists() {
        WhitelistEntry entry = new WhitelistEntry("123");
        when(whitelistService.findAll()).thenReturn(List.of(entry));
        when(whitelistService.isEnabled()).thenReturn(false);

        fr.enimaloc.catapult.domain.UserAccount account = new fr.enimaloc.catapult.domain.UserAccount();
        account.setTwitchUsername("streamer42");
        when(userAccountRepository.findByTwitchId("123")).thenReturn(Optional.of(account));

        Model model = new ExtendedModelMap();
        controller.page(model);

        @SuppressWarnings("unchecked")
        java.util.Map<String, String> usernames = (java.util.Map<String, String>) model.getAttribute("resolvedUsernames");
        assertThat(usernames).containsEntry("123", "streamer42");
    }

    @Test
    void page_usesDashWhenAccountDoesNotExist() {
        WhitelistEntry entry = new WhitelistEntry("999");
        when(whitelistService.findAll()).thenReturn(List.of(entry));
        when(whitelistService.isEnabled()).thenReturn(false);
        when(userAccountRepository.findByTwitchId("999")).thenReturn(Optional.empty());

        Model model = new ExtendedModelMap();
        controller.page(model);

        @SuppressWarnings("unchecked")
        java.util.Map<String, String> usernames = (java.util.Map<String, String>) model.getAttribute("resolvedUsernames");
        assertThat(usernames).containsEntry("999", "—");
    }

    @Test
    void toggle_flipsEnabledState_andRedirects() {
        when(whitelistService.isEnabled()).thenReturn(false);
        String view = controller.toggle();
        verify(whitelistService).setEnabled(true);
        assertThat(view).isEqualTo("redirect:/admin/whitelist");
    }

    @Test
    void add_callsServiceAndRedirects() {
        String view = controller.add("  456  ");
        verify(whitelistService).add("456");
        assertThat(view).isEqualTo("redirect:/admin/whitelist");
    }

    @Test
    void delete_callsServiceAndRedirects() {
        String view = controller.delete("789");
        verify(whitelistService).remove("789");
        assertThat(view).isEqualTo("redirect:/admin/whitelist");
    }
}
