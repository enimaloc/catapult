package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.channel.ChannelPageData;
import fr.enimaloc.catapult.common.dto.channel.ChannelUserDto;
import fr.enimaloc.catapult.common.dto.channel.ChessData;
import fr.enimaloc.catapult.common.dto.channel.DtddMappingStatusDto;
import fr.enimaloc.catapult.common.dto.channel.FaceitData;
import fr.enimaloc.catapult.common.dto.channel.PagedBindings;
import fr.enimaloc.catapult.common.dto.channel.UserSettingsDto;
import fr.enimaloc.catapult.common.dto.channel.XboxData;
import fr.enimaloc.catapult.config.TwitchatCatalogConfig;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The channel's API tab: the owner's alone, it holds the personal tokens, a link to the API documentation and the
 * cards that only feed the personal stats (IntegrationCards.API_TAB), which the Integrations tab no longer shows.
 */
@WebMvcTest(controllers = {IndexController.class, SpaFragmentController.class})
@Import({ModelFiller.class, WebSecurityConfig.class, TwitchatCatalogConfig.class})
class ChannelApiTabRenderingTest {

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean NavEventStreamController navEvents;

    @BeforeEach
    void stubSettingsAndDtddMapping() {
        lenient().when(apiService.channelSettings(any())).thenReturn(new UserSettingsDto(
                false, Set.of(), null, null, Set.of(), false, false, false,
                null, null, Set.of(), List.of(), false, Set.of(), List.of(), null, false));
        lenient().when(apiService.dtddMappingStatus(any())).thenReturn(new DtddMappingStatusDto(null, null, false, null));
    }

    private static ChannelPageData page(boolean owner, FaceitData faceit, ChessData chess) {
        return new ChannelPageData(new ChannelUserDto("id-1", "twitch-1", "enimaloc", "https://example.test/avatar.png"),
                "enimaloc", owner, true, true, null, new PagedBindings(0, 1, 0, List.of()), List.of(), Set.of(),
                List.of(), Set.of(), null, null, null, owner ? new XboxData(true) : null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, faceit, chess);
    }

    private String render(ChannelPageData data) throws Exception {
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(data);
        return mvc.perform(get("/spa/channel/enimaloc").locale(Locale.ENGLISH))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static String panel(String html, String tab) {
        int start = html.indexOf("<mdui-tab-panel slot=\"panel\" value=\"" + tab + "\"");
        assertThat(start).as("%s panel", tab).isNotNegative();
        int end = html.indexOf("</mdui-tab-panel>", start);
        return html.substring(start, end);
    }

    @Test
    void theOwner_getsTheApiTab_rightAfterIntegrations() throws Exception {
        String html = render(page(true, null, null));

        int integrations = html.indexOf("<mdui-tab value=\"integrations\"");
        int api = html.indexOf("<mdui-tab value=\"api\"");
        assertThat(integrations).isNotNegative();
        assertThat(api).isGreaterThan(integrations);
        assertThat(html.indexOf("<mdui-tab value=\"", integrations + 1)).isEqualTo(api);
    }

    @Test
    void theApiTab_holdsTheTokens_andLinksToTheDocumentation() throws Exception {
        String html = render(page(true, null, null));

        String api = panel(html, "api");
        assertThat(api).contains("id=\"channel-read-token-card\"", "id=\"channel-api-docs-link\"", "href=\"/docs/api\"",
                "data-link", "Read the API documentation");
        assertThat(panel(html, "account")).doesNotContain("read-token", "api-token");
    }

    @Test
    void theStatsCards_areOnTheApiTab_theDetectionOnesOnIntegrations() throws Exception {
        String html = render(page(true, new FaceitData("s1mple", null, true), new ChessData("hikaru", null)));

        String api = panel(html, "api");
        assertThat(api).contains("id=\"channel-api-connections\"", "data-conn=\"faceit\"", "data-conn=\"chess\"",
                "id=\"channel-api-stats-intro\"").doesNotContain("data-conn=\"xbox\"", "data-conn=\"twitch\"");
        String integrations = panel(html, "integrations");
        assertThat(integrations).contains("id=\"channel-connections\"", "data-conn=\"twitch\"", "data-conn=\"xbox\"")
                .doesNotContain("data-conn=\"faceit\"", "data-conn=\"chess\"");
        // Both grids share the layout.
        assertThat(api).contains("class=\"connection-grid\"");
        assertThat(integrations).contains("class=\"connection-grid\"");
    }

    @Test
    void withoutStatsCards_theApiTabHasNoEmptyGrid() throws Exception {
        String api = panel(render(page(true, null, null)), "api");

        assertThat(api).doesNotContain("channel-api-connections", "channel-api-stats-intro");
    }

    @Test
    void aModerator_getsNoApiTab() throws Exception {
        String html = render(page(false, null, null));

        assertThat(html).doesNotContain("value=\"api\"", "channel-api-docs-link", "channel-read-token-card");
    }

    @Test
    void theApiTab_carriesTheNewFeatureDot_untilSeen() throws Exception {
        // The dot shows for a month after the release: the render check lapses with it.
        org.junit.jupiter.api.Assumptions.assumeTrue(LocalDate.now(ZoneOffset.UTC).isBefore(LocalDate.of(2026, 12, 23)));
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(page(true, null, null));
        when(apiService.seenFeatures()).thenReturn(List.of());

        String unseen = mvc.perform(get("/spa/channel/enimaloc").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(unseen).contains("data-new-feature=\"channel.api\"");

        when(apiService.seenFeatures()).thenReturn(List.of("channel.api"));
        String seen = mvc.perform(get("/spa/channel/enimaloc").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(seen).doesNotContain("data-new-feature=\"channel.api\"");
    }

    @Test
    void theFrenchTab_speaksToTheMember() throws Exception {
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(page(true, null, null));

        String html = mvc.perform(get("/spa/channel/enimaloc").locale(Locale.FRENCH))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(panel(html, "api")).contains("Lire la documentation de l&#39;API", "Ton API personnelle");
    }
}
