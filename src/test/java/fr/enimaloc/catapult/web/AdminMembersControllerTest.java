package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.security.CatapultOAuth2User;
import fr.enimaloc.catapult.service.AccountService;
import fr.enimaloc.catapult.service.StreamStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminMembersControllerTest {

    @Mock private UserAccountRepository userAccountRepository;
    @Mock private StreamStateService streamStateService;
    @Mock private Environment environment;
    @Mock private AccountService accountService;
    private AdminMembersController controller;

    @BeforeEach
    void setup() {
        controller = new AdminMembersController(userAccountRepository, streamStateService, environment, Optional.empty(), accountService);
    }

    private CatapultOAuth2User adminPrincipal(String twitchId) {
        UserAccount account = new UserAccount();
        account.setId(UUID.randomUUID());
        account.setTwitchId(twitchId);
        account.setTwitchUsername("admin");
        account.setStatus(UserAccount.Status.ACTIVE);
        return CatapultOAuth2User.forImpersonation(account, true);
    }

    @Test
    void page_populatesMembersAndLiveStatus() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setTwitchId("user1");
        user.setTwitchUsername("streamer");
        user.setStatus(UserAccount.Status.ACTIVE);

        when(userAccountRepository.findAll()).thenReturn(List.of(user));
        when(streamStateService.isLive(user)).thenReturn(true);
        when(environment.getActiveProfiles()).thenReturn(new String[]{});

        Model model = new ExtendedModelMap();
        String view = controller.page(model, adminPrincipal("adminId"));

        assertThat(view).isEqualTo("admin/members");
        assertThat(model.getAttribute("members")).isEqualTo(List.of(user));
        @SuppressWarnings("unchecked")
        Map<UUID, Boolean> liveStatus = (Map<UUID, Boolean>) model.getAttribute("liveStatus");
        assertThat(liveStatus).containsEntry(user.getId(), true);
    }

    @Test
    void page_noMockProfile_isMockProfileFalse() {
        when(userAccountRepository.findAll()).thenReturn(List.of());
        when(environment.getActiveProfiles()).thenReturn(new String[]{});

        Model model = new ExtendedModelMap();
        controller.page(model, adminPrincipal("adminId"));

        assertThat(model.getAttribute("isMockProfile")).isEqualTo(false);
    }

    @Test
    void page_mockProfile_isMockProfileTrue() {
        when(userAccountRepository.findAll()).thenReturn(List.of());
        when(environment.getActiveProfiles()).thenReturn(new String[]{"mock"});

        Model model = new ExtendedModelMap();
        controller.page(model, adminPrincipal("adminId"));

        assertThat(model.getAttribute("isMockProfile")).isEqualTo(true);
    }

    @Test
    void page_currentUserTwitchIdInModel() {
        when(userAccountRepository.findAll()).thenReturn(List.of());
        when(environment.getActiveProfiles()).thenReturn(new String[]{});

        Model model = new ExtendedModelMap();
        controller.page(model, adminPrincipal("myTwitchId"));

        assertThat(model.getAttribute("currentUserTwitchId")).isEqualTo("myTwitchId");
    }

    @Test
    void deleteAccount_callsServiceAndRedirects() {
        UserAccount target = new UserAccount();
        target.setId(UUID.randomUUID());
        target.setTwitchId("target123");
        target.setTwitchUsername("target");
        target.setStatus(UserAccount.Status.ACTIVE);

        when(userAccountRepository.findById(target.getId())).thenReturn(Optional.of(target));

        String view = controller.deleteAccount(target.getId(), adminPrincipal("adminId"));

        verify(accountService).deleteAccountImmediately(target);
        assertThat(view).isEqualTo("redirect:/admin/members");
    }

    @Test
    void deleteAccount_selfDeletion_throws403() {
        UserAccount self = new UserAccount();
        self.setId(UUID.randomUUID());
        self.setTwitchId("adminId");
        self.setTwitchUsername("admin");
        self.setStatus(UserAccount.Status.ACTIVE);

        when(userAccountRepository.findById(self.getId())).thenReturn(Optional.of(self));

        assertThatThrownBy(() -> controller.deleteAccount(self.getId(), adminPrincipal("adminId")))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        verify(accountService, never()).deleteAccountImmediately(any());
    }

    @Test
    void deleteAccount_unknownId_throws404() {
        UUID unknownId = UUID.randomUUID();
        when(userAccountRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.deleteAccount(unknownId, adminPrincipal("adminId")))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        verify(accountService, never()).deleteAccountImmediately(any());
    }

    @Test
    void unlinkSteam_callsDisconnectAndRedirects() {
        UserAccount target = new UserAccount();
        target.setId(UUID.randomUUID());
        target.setTwitchId("target123");
        target.setTwitchUsername("target");
        target.setSteamId("76561198000000000");
        target.setStatus(UserAccount.Status.ACTIVE);

        when(userAccountRepository.findById(target.getId())).thenReturn(Optional.of(target));

        String view = controller.unlinkSteam(target.getId());

        verify(accountService).disconnectProvider(target, OAuthToken.Provider.STEAM);
        assertThat(view).isEqualTo("redirect:/admin/members");
    }

    @Test
    void unlinkSteam_noSteamId_throws400() {
        UserAccount target = new UserAccount();
        target.setId(UUID.randomUUID());
        target.setSteamId(null);
        target.setStatus(UserAccount.Status.ACTIVE);

        when(userAccountRepository.findById(target.getId())).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> controller.unlinkSteam(target.getId()))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(accountService, never()).disconnectProvider(any(), any());
    }

    @Test
    void unlinkSteam_unknownId_throws404() {
        UUID unknownId = UUID.randomUUID();
        when(userAccountRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.unlinkSteam(unknownId))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        verify(accountService, never()).disconnectProvider(any(), any());
    }
}
