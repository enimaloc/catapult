package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.security.CatapultOAuth2User;
import fr.enimaloc.catapult.security.CatapultOAuth2UserService;
import fr.enimaloc.catapult.service.ChannelAccessService;
import fr.enimaloc.catapult.service.ExperimentService;
import fr.enimaloc.catapult.service.StreamStateService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ChannelsListController.class)
class ChannelsListControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean ChannelAccessService channelAccessService;
    @MockitoBean StreamStateService streamStateService;
    @MockitoBean CatapultOAuth2UserService oAuth2UserService;
    @MockitoBean ExperimentService experimentService;

    private UserAccount viewer;
    private UsernamePasswordAuthenticationToken auth;

    @BeforeEach
    void setup() {
        viewer = new UserAccount();
        viewer.setId(UUID.randomUUID());
        viewer.setTwitchId("viewer-id");
        viewer.setTwitchUsername("mystream");
        viewer.setStatus(UserAccount.Status.ACTIVE);

        var oAuth2User = mock(OAuth2User.class);
        when(oAuth2User.getAttributes()).thenReturn(Map.of());
        var catapultUser = new CatapultOAuth2User(oAuth2User, viewer, false);
        auth = new UsernamePasswordAuthenticationToken(
            catapultUser, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Test
    void redirects_to_own_channel_when_only_one_accessible() throws Exception {
        when(channelAccessService.getAccessibleChannels(viewer)).thenReturn(List.of(viewer));

        mockMvc.perform(get("/channels").with(authentication(auth)))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/channels/mystream"));
    }

    @Test
    void shows_channel_list_when_multiple_accessible() throws Exception {
        UserAccount other = new UserAccount();
        other.setId(UUID.randomUUID());
        other.setTwitchId("other-id");
        other.setTwitchUsername("otherstream");
        other.setStatus(UserAccount.Status.ACTIVE);

        when(channelAccessService.getAccessibleChannels(viewer)).thenReturn(List.of(viewer, other));
        when(streamStateService.isLive(any())).thenReturn(false);

        mockMvc.perform(get("/channels").with(authentication(auth)))
            .andExpect(status().isOk())
            .andExpect(view().name("channels"))
            .andExpect(model().attributeExists("channels"))
            .andExpect(model().attributeExists("liveStatus"))
            .andExpect(model().attribute("viewerTwitchId", "viewer-id"));
    }

    @Test
    void channel_grid_is_rendered_for_owner() throws Exception {
        when(channelAccessService.getAccessibleChannels(viewer)).thenReturn(List.of(viewer, otherChannel()));
        when(streamStateService.isLive(any())).thenReturn(false);

        MvcResult result = mockMvc.perform(get("/channels").with(authentication(auth)))
            .andExpect(status().isOk())
            .andReturn();

        Document doc = Jsoup.parse(result.getResponse().getContentAsString());
        assertThat(doc.select(".channel-card")).hasSize(2);
        assertThat(doc.select(".badge-owner")).hasSize(1);
        assertThat(doc.select(".channel-card").get(0).select(".badge-owner")).isNotEmpty();
        assertThat(doc.select(".channel-card").get(1).select(".badge-moderator")).isNotEmpty();
    }

    @Test
    void live_card_has_live_badge_and_modifier_class() throws Exception {
        when(channelAccessService.getAccessibleChannels(viewer)).thenReturn(List.of(viewer, otherChannel()));
        when(streamStateService.isLive(any())).thenAnswer(inv -> inv.getArgument(0).equals(viewer));

        MvcResult result = mockMvc.perform(get("/channels").with(authentication(auth)))
            .andExpect(status().isOk())
            .andReturn();

        Document doc = Jsoup.parse(result.getResponse().getContentAsString());
        assertThat(doc.select(".channel-card--live")).hasSize(1);
        var liveCard = doc.selectFirst(".channel-card--live");
        assertThat(liveCard).isNotNull();
        assertThat(liveCard.select(".channel-card__live-badge")).hasSize(1);
    }

    @Test
    void shows_empty_state_when_no_channels_accessible() throws Exception {
        when(channelAccessService.getAccessibleChannels(viewer)).thenReturn(List.of());

        MvcResult result = mockMvc.perform(get("/channels").with(authentication(auth)))
            .andExpect(status().isOk())
            .andReturn();

        Document doc = Jsoup.parse(result.getResponse().getContentAsString());
        assertThat(doc.select(".channel-empty-state")).isNotEmpty();
        assertThat(doc.select(".channel-grid")).isEmpty();
    }

    private UserAccount otherChannel() {
        UserAccount other = new UserAccount();
        other.setId(UUID.randomUUID());
        other.setTwitchId("other-id");
        other.setTwitchUsername("otherstream");
        other.setStatus(UserAccount.Status.ACTIVE);
        return other;
    }
}
