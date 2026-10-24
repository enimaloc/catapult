package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.binding.CustomBindingDto;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingsStateDto;
import fr.enimaloc.catapult.common.dto.channel.ChannelPageData;
import fr.enimaloc.catapult.common.dto.channel.ChannelUserDto;
import fr.enimaloc.catapult.common.dto.channel.DtddMappingStatusDto;
import fr.enimaloc.catapult.common.dto.channel.PagedBindings;
import fr.enimaloc.catapult.common.dto.channel.UserSettingsDto;
import fr.enimaloc.catapult.config.TwitchatCatalogConfig;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The custom bindings cards of the channel page: the overview's buttons and the settings' management and API token
 * cards exist for the owner while the experiment is open to the channel, and nowhere else.
 */
@WebMvcTest(controllers = {IndexController.class, SpaFragmentController.class})
@Import({ModelFiller.class, WebSecurityConfig.class, TwitchatCatalogConfig.class})
class ChannelCustomBindingsRenderingTest {
    private static final UUID ART = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEV = UUID.fromString("22222222-2222-2222-2222-222222222222");

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

    private static CustomBindingsStateDto state() {
        return new CustomBindingsStateDto(true, List.of(
                new CustomBindingDto(ART, "Art", "509660", "Art", List.of(), List.of(), false),
                new CustomBindingDto(DEV, "Développement", "1469308723", "Software and Game Development",
                        List.of(), List.of(), true)), ART, Instant.parse("2026-10-24T10:00:00Z"));
    }

    private String render(boolean owner, CustomBindingsStateDto bindings) throws Exception {
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(new ChannelPageData(
                new ChannelUserDto("id-1", "twitch-1", "enimaloc", "https://example.test/avatar.png"),
                "enimaloc", owner, true, true, null,
                new PagedBindings(0, 1, 0, List.of()),
                List.of(), Set.of(), List.of(), Set.of(),
                null, null, null, null, null, null, null, "uuid", bindings));
        return mvc.perform(get("/spa/channel/enimaloc").locale(Locale.ENGLISH))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void ownerWithTheExperimentOpen_getsTheButtonsTheManagementAndTheTokenCard() throws Exception {
        String html = render(true, state());

        assertThat(html).contains("id=\"channel-custom-bindings-card\"", "id=\"channel-custom-bindings-settings\"",
                "id=\"channel-api-token-card\"", "id=\"custom-bindings-error\"");
        assertThat(html).contains("data-custom-binding-id=\"" + ART + "\"", "data-custom-binding-id=\"" + DEV + "\"");
        assertThat(html).contains("Développement", "Uninterruptible", "Deactivate");
        // The active binding is the highlighted one, the other one is not.
        assertThat(html).containsPattern("custom-binding-row is-active[^>]*data-custom-binding-id=\"" + ART)
                .doesNotContainPattern("custom-binding-row is-active[^>]*data-custom-binding-id=\"" + DEV);
        assertThat(html).contains("20 bindings maximum", "Twitch did not apply the category");
    }

    @Test
    void experimentClosed_hidesEveryCard() throws Exception {
        assertThat(render(true, null))
                .doesNotContain("channel-custom-bindings-card").doesNotContain("channel-custom-bindings-settings")
                .doesNotContain("channel-api-token-card").doesNotContain("custom-binding");
    }

    @Test
    void aModerator_getsNoCard() throws Exception {
        assertThat(render(false, null)).doesNotContain("channel-custom-bindings-card").doesNotContain("channel-api-token-card");
    }

    @Test
    void frenchTextsAreServed() throws Exception {
        when(apiService.channelPage(any(), anyInt(), any(), any())).thenReturn(new ChannelPageData(
                new ChannelUserDto("id-1", "twitch-1", "enimaloc", "https://example.test/avatar.png"),
                "enimaloc", true, true, true, null, new PagedBindings(0, 1, 0, List.of()),
                List.of(), Set.of(), List.of(), Set.of(), null, null, null, null, null, null, null, "uuid", state()));
        String html = mvc.perform(get("/spa/channel/enimaloc").locale(Locale.FRENCH))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Reconnecte ton compte Twitch", "Catapult est en pause sur cette chaîne",
                "20 bindings maximum", "Twitch n&#39;a pas appliqué la catégorie");
    }
}
