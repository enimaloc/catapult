package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.getter.MockSteamApiClient;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.MockTwitchEventSubService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminMembersMockControllerTest {

    @Mock private UserAccountRepository userAccountRepository;
    @Mock private MockTwitchEventSubService mockTwitchEventSubService;
    @Mock private MockSteamApiClient mockSteamApiClient;
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
}
