package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ChannelPageController.ChannelPageData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChannelPageControllerTest {

    @Mock ApiClient apiClient;

    ChannelPageController controller;

    ChannelPageController newController() {
        return new ChannelPageController(apiClient);
    }

    ChannelPageData ownerData() {
        return new ChannelPageData(
                null, "streamer", true, false, false, null, null,
                null, null, null, null, null, null,
                false, false, false, false, false, false, false, 0, false, false);
    }

    ChannelPageData moderatorData() {
        return new ChannelPageData(
                null, "streamer", false, false, false, null, null,
                null, null, null, null, null, null,
                false, false, false, false, false, false, false, 0, false, false);
    }

    void stubChannelData(ChannelPageData data) {
        when(apiClient.get(eq("/api/channels/{username}?page={page}"), eq(ChannelPageData.class), eq("streamer"), eq(0)))
                .thenReturn(data);
        if (data == null) return;
        // populateModel always fetches these regardless of the owner/moderator branch under test.
        org.mockito.Mockito.lenient()
                .when(apiClient.get(eq("/api/channels/{username}/settings"), eq(ChannelPageController.UserSettingsDto.class), eq("streamer")))
                .thenReturn(null);
        org.mockito.Mockito.lenient()
                .when(apiClient.get(eq("/api/channels/{username}/dtdd-mapping"), eq(ChannelPageController.DtddMappingStatusDto.class), eq("streamer")))
                .thenReturn(null);
        if (data.isOwner()) {
            org.mockito.Mockito.lenient().when(apiClient.minecraftLinkState()).thenReturn(null);
            org.mockito.Mockito.lenient()
                    .when(apiClient.get(eq("/api/channels/{username}/settings/twitchat"), eq(Map.class), eq("streamer")))
                    .thenReturn(null);
        }
    }

    @Test
    void ownerRendersAppTabbedWithDefaultDashboardTab() {
        controller = newController();
        stubChannelData(ownerData());
        Model model = new ExtendedModelMap();

        String view = controller.channelPage("streamer", null, 0, null, null, model);

        assertThat(view).isEqualTo("app-tabbed");
        assertThat(model.getAttribute("activeTab")).isEqualTo("dashboard");
    }

    @Test
    void ownerHonoursRequestedTab() {
        controller = newController();
        stubChannelData(ownerData());
        Model model = new ExtendedModelMap();

        String view = controller.channelPage("streamer", "configuration", 0, null, null, model);

        assertThat(view).isEqualTo("app-tabbed");
        assertThat(model.getAttribute("activeTab")).isEqualTo("configuration");
    }

    @Test
    void moderatorRendersAppTabbedWithDefaultDashboardTab() {
        controller = newController();
        stubChannelData(moderatorData());
        Model model = new ExtendedModelMap();

        String view = controller.channelPage("streamer", null, 0, null, null, model);

        assertThat(view).isEqualTo("app-tabbed");
        assertThat(model.getAttribute("activeTab")).isEqualTo("dashboard");
    }

    @Test
    void missingChannelRedirectsToChannelsList() {
        controller = newController();
        stubChannelData(null);
        Model model = new ExtendedModelMap();

        String view = controller.channelPage("streamer", null, 0, null, null, model);

        assertThat(view).isEqualTo("redirect:/channels");
    }

    @Test
    void tabFragmentForbiddenWhenChannelMissing() {
        controller = newController();
        stubChannelData(null);
        Model model = new ExtendedModelMap();

        assertThatThrownBy(() -> controller.tabFragment("streamer", "configuration", model))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    void tabFragmentRendersFragmentSelectorForOwner() {
        controller = newController();
        stubChannelData(ownerData());
        Model model = new ExtendedModelMap();

        String view = controller.tabFragment("streamer", "configuration", model);

        assertThat(view).isEqualTo("fragments/configuration-tab :: configuration-tab");
    }

    @Test
    void tabFragmentRendersFragmentSelectorForModerator() {
        controller = newController();
        stubChannelData(moderatorData());
        Model model = new ExtendedModelMap();

        String view = controller.tabFragment("streamer", "configuration", model);

        assertThat(view).isEqualTo("fragments/configuration-tab :: configuration-tab");
    }

    @Test
    void invitationsTabRedirectsWhenVariantIsNotTab() {
        controller = newController();
        stubChannelData(ownerData());
        Model model = new ExtendedModelMap();
        model.addAttribute("invitePlacementVariant", "nav-default");

        String view = controller.channelPage("streamer", "invitations", 0, null, null, model);

        assertThat(view).isEqualTo("redirect:/channels/{username}");
    }

    @Test
    void invitationsTabRendersWhenVariantIsTab() {
        controller = newController();
        stubChannelData(ownerData());
        Model model = new ExtendedModelMap();
        model.addAttribute("invitePlacementVariant", "tab");
        org.mockito.Mockito.lenient().when(apiClient.get(eq("/api/invite"), eq(Map.class))).thenReturn(null);

        String view = controller.channelPage("streamer", "invitations", 0, null, null, model);

        assertThat(view).isEqualTo("app-tabbed");
        assertThat(model.getAttribute("activeTab")).isEqualTo("invitations");
    }

    @Test
    void invitationsTabFragmentForbiddenWhenVariantIsNotTab() {
        controller = newController();
        stubChannelData(ownerData());
        Model model = new ExtendedModelMap();
        model.addAttribute("invitePlacementVariant", "card");

        assertThatThrownBy(() -> controller.tabFragment("streamer", "invitations", model))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }
}
