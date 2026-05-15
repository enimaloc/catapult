package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.security.CatapultOAuth2User;
import fr.enimaloc.catapult.service.StreamStateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminMembersControllerTest {

    @Mock private UserAccountRepository userAccountRepository;
    @Mock private StreamStateService streamStateService;
    @Mock private Environment environment;
    @InjectMocks private AdminMembersController controller;

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
}
