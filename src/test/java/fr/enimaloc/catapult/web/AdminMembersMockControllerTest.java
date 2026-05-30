package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.getter.MockSteamApiClient;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.IgdbService;
import fr.enimaloc.catapult.service.MockTwitchEventSubService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminMembersMockControllerTest {

    @Mock private UserAccountRepository userAccountRepository;
    @Mock private MockTwitchEventSubService mockTwitchEventSubService;
    @Mock private MockSteamApiClient mockSteamApiClient;
    @Mock private IgdbService igdbService;
    @InjectMocks private AdminMembersMockController controller;

    private UserAccount userWithSteam(UUID id) {
        UserAccount user = new UserAccount();
        user.setId(id);
        user.setSteamId("steam123");
        return user;
    }

    @Test
    void setTwitchOnline_delegatesToService_andRedirects() {
        UUID id = UUID.randomUUID();
        UserAccount user = new UserAccount(); user.setId(id);
        when(userAccountRepository.findById(id)).thenReturn(Optional.of(user));

        String result = controller.setTwitchOnline(id);

        verify(mockTwitchEventSubService).setOnline(user);
        assertThat(result).isEqualTo("redirect:/admin/members");
    }

    @Test
    void setTwitchOnline_userNotFound_throws404() {
        UUID id = UUID.randomUUID();
        when(userAccountRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.setTwitchOnline(id))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void setTwitchOffline_userNotFound_throws404() {
        UUID id = UUID.randomUUID();
        when(userAccountRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.setTwitchOffline(id))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void setSteamGame_userNotFound_throws404() {
        UUID id = UUID.randomUUID();
        when(userAccountRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.setSteamGame(id, "570", "Dota 2"))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void clearSteamGame_userNotFound_throws404() {
        UUID id = UUID.randomUUID();
        when(userAccountRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.clearSteamGame(id))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void setTwitchOffline_delegatesToService_andRedirects() {
        UUID id = UUID.randomUUID();
        UserAccount user = new UserAccount(); user.setId(id);
        when(userAccountRepository.findById(id)).thenReturn(Optional.of(user));

        String result = controller.setTwitchOffline(id);

        verify(mockTwitchEventSubService).setOffline(user);
        assertThat(result).isEqualTo("redirect:/admin/members");
    }

    @Test
    void setSteamGame_delegatesToClient_andRedirects() {
        UUID id = UUID.randomUUID();
        UserAccount user = userWithSteam(id);
        when(userAccountRepository.findById(id)).thenReturn(Optional.of(user));

        String result = controller.setSteamGame(id, "570", "Dota 2");

        verify(mockSteamApiClient).setGameForUser("steam123", "570", "Dota 2");
        assertThat(result).isEqualTo("redirect:/admin/members");
    }

    @Test
    void setSteamGame_userHasNoSteamId_throws400() {
        UUID id = UUID.randomUUID();
        UserAccount user = new UserAccount(); user.setId(id);
        when(userAccountRepository.findById(id)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> controller.setSteamGame(id, "570", "Dota 2"))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void clearSteamGame_delegatesToClient_andRedirects() {
        UUID id = UUID.randomUUID();
        UserAccount user = userWithSteam(id);
        when(userAccountRepository.findById(id)).thenReturn(Optional.of(user));

        String result = controller.clearSteamGame(id);

        verify(mockSteamApiClient).clearGameForUser("steam123");
        assertThat(result).isEqualTo("redirect:/admin/members");
    }

    @Test
    void searchIgdbGames_returnsResults() {
        when(igdbService.searchGames("zelda"))
            .thenReturn(List.of(new IgdbService.IgdbGame("1942", "The Legend of Zelda")));

        List<Map<String, String>> result = controller.searchIgdbGames("zelda");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("id")).isEqualTo("1942");
        assertThat(result.get(0).get("name")).isEqualTo("The Legend of Zelda");
    }

    @Test
    void searchIgdbGames_tooShortQuery_returnsEmpty() {
        List<Map<String, String>> result = controller.searchIgdbGames("z");
        assertThat(result).isEmpty();
        verifyNoInteractions(igdbService);
    }

    @Test
    void searchIgdbGames_blankQuery_returnsEmpty() {
        List<Map<String, String>> result = controller.searchIgdbGames("  ");
        assertThat(result).isEmpty();
        verifyNoInteractions(igdbService);
    }
}
