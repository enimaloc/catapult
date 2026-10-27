package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.twitchat.TwitchatWidgetAccessResponse;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatWidgetConfig;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import fr.enimaloc.catapult.twitchat.TwitchatDispatchHub;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = WidgetObsController.class)
@Import({WebSecurityConfig.class, fr.enimaloc.catapult.config.I18nConfiguration.class})
class WidgetObsControllerTest {

    private static final UUID TOKEN = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID OWNER = UUID.fromString("99999999-8888-7777-6666-555555555555");

    private static ApiClient.Result<TwitchatWidgetAccessResponse> access(String owner, boolean enabled) {
        return new ApiClient.Result<>(200, new TwitchatWidgetAccessResponse(owner, enabled));
    }

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean TwitchatDispatchHub hub;

    @Test
    void page_isBare_noindex_withTheTwoUrls() throws Exception {
        when(apiService.widgetAccess(TOKEN)).thenReturn(access(OWNER.toString(), true));

        mvc.perform(get("/widget/obs/" + TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<meta name=\"robots\" content=\"noindex\">")))
                .andExpect(content().string(containsString("data-obs-config-url=\"/widget/obs/" + TOKEN + "/config\"")))
                .andExpect(content().string(containsString("data-twitchat-events-url=\"/widget/obs/" + TOKEN + "/events\"")))
                .andExpect(content().string(containsString("<div id=\"log\"></div>")))
                .andExpect(content().string(containsString("#log{display:block}")))
                .andExpect(content().string(containsString("src=\"/js/suggest.js\"")))
                .andExpect(content().string(containsString("src=\"/js/obs.js\"")))
                .andExpect(content().string(containsString("src=\"/js/obs-session.js\"")))
                .andExpect(content().string(containsString("src=\"/js/twitchat-protocol.js\"")))
                .andExpect(content().string(containsString("src=\"/js/twitchat.js\"")))
                .andExpect(content().string(containsString("src=\"/js/twitchat-stream.js\"")))
                // Bare: none of the app's shell.
                .andExpect(content().string(not(containsString("mdui"))))
                .andExpect(content().string(not(containsString("spa.js"))))
                .andExpect(content().string(not(containsString("data-disabled"))))
                .andExpect(content().string(not(containsString("http-equiv=\"refresh\""))));
    }

    /** A catapult-api blip while OBS loads the page: its scripts are there, retrying the config until it comes back. */
    @Test
    void page_isTitledInTheVisitorsLanguage() throws Exception {
        when(apiService.widgetAccess(TOKEN)).thenReturn(access(OWNER.toString(), true));

        mvc.perform(get("/widget/obs/" + TOKEN).locale(java.util.Locale.FRENCH))
                .andExpect(content().string(containsString("<title>Catapult — relais Twitchat</title>")));
        mvc.perform(get("/widget/obs/" + TOKEN).locale(java.util.Locale.ENGLISH))
                .andExpect(content().string(containsString("<title>Catapult — Twitchat relay</title>")));
    }

    @Test
    void page_keepsItsScripts_whenCatapultApiIsUnreachable() throws Exception {
        when(apiService.widgetAccess(TOKEN)).thenReturn(new ApiClient.Result<>(502, null));

        mvc.perform(get("/widget/obs/" + TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-obs-config-url=\"/widget/obs/" + TOKEN + "/config\"")))
                .andExpect(content().string(containsString("data-twitchat-events-url=\"/widget/obs/" + TOKEN + "/events\"")))
                .andExpect(content().string(containsString("src=\"/js/obs-session.js\"")))
                .andExpect(content().string(not(containsString("data-disabled"))))
                .andExpect(content().string(not(containsString("widget disabled"))));
        mvc.perform(get("/widget/obs/" + TOKEN + "/config")).andExpect(status().isNoContent());
        mvc.perform(get("/widget/obs/" + TOKEN + "/events")).andExpect(status().isNotFound());
        verify(hub, never()).subscribeWidget(any(), any(), any(), any(Duration.class));
    }

    /** Review Focus 4: never a 500 nor a stack trace, whatever the token. */
    @Test
    void notAUuid_unknown_disabled_neverFail() throws Exception {
        UUID unknown = UUID.randomUUID();
        UUID disabled = UUID.randomUUID();
        when(apiService.widgetAccess(unknown)).thenReturn(new ApiClient.Result<>(404, null));
        when(apiService.widgetAccess(disabled)).thenReturn(access(OWNER.toString(), false));
        when(apiService.widgetObsConfig(any())).thenReturn(null);

        for (String token : new String[]{"not-a-uuid", unknown.toString(), disabled.toString()}) {
            mvc.perform(get("/widget/obs/" + token))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("data-disabled")))
                    .andExpect(content().string(containsString("widget disabled")))
                    // Checked again now and then: switching the widget on reaches OBS without touching it.
                    .andExpect(content().string(containsString("<meta http-equiv=\"refresh\" content=\"60\">")))
                    .andExpect(content().string(not(containsString("obs-session.js"))))
                    .andExpect(content().string(not(containsString("data-obs-config-url"))));
            mvc.perform(get("/widget/obs/" + token + "/config")).andExpect(status().isNoContent());
            mvc.perform(get("/widget/obs/" + token + "/events")).andExpect(status().isNotFound());
        }
        // An owner id that isn't a UUID (never sent by catapult-api, but never a 500 either).
        when(apiService.widgetAccess(TOKEN)).thenReturn(access("oops", true));
        mvc.perform(get("/widget/obs/" + TOKEN + "/events")).andExpect(status().isNotFound());

        verify(hub, never()).subscribeWidget(any(), any(), any(), any(Duration.class));
    }

    @Test
    void config_isTheWidgetsObsConnection_uncached() throws Exception {
        when(apiService.widgetObsConfig(TOKEN)).thenReturn(new TwitchatWidgetConfig("10.0.0.2", 4456, "pw", "auto"));

        mvc.perform(get("/widget/obs/" + TOKEN + "/config"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"obsHost\":\"10.0.0.2\",\"obsPort\":4456,\"obsPassword\":\"pw\",\"twitchatBranch\":\"auto\"}"));
    }

    @Test
    void notAUuid_neverReachesCatapultApi() throws Exception {
        mvc.perform(get("/widget/obs/not-a-uuid")).andExpect(status().isOk());
        mvc.perform(get("/widget/obs/not-a-uuid/config")).andExpect(status().isNoContent());
        mvc.perform(get("/widget/obs/not-a-uuid/events")).andExpect(status().isNotFound());
        verify(apiService, never()).widgetAccess(any());
        verify(apiService, never()).widgetObsConfig(any());
    }

    @Test
    void events_subscribeTheOwner_whenEnabled() throws Exception {
        when(apiService.widgetAccess(TOKEN)).thenReturn(access(OWNER.toString(), true));
        when(hub.subscribeWidget(OWNER, TOKEN, "127.0.0.1", WidgetObsController.STREAM_TIMEOUT)).thenReturn(new SseEmitter(600_000L));

        mvc.perform(get("/widget/obs/" + TOKEN + "/events")).andExpect(request().asyncStarted());
        // Shorter than a page's stream: a revoked link stops at the next reconnection.
        assertThat(WidgetObsController.STREAM_TIMEOUT).isEqualTo(Duration.ofMinutes(10));
        verify(hub).subscribeWidget(OWNER, TOKEN, "127.0.0.1", WidgetObsController.STREAM_TIMEOUT);
        verify(hub, never()).subscribe(any(UUID.class), any());
    }

    @Test
    void events_pastTheOverallBound_areRefused() throws Exception {
        when(apiService.widgetAccess(TOKEN)).thenReturn(access(OWNER.toString(), true));

        mvc.perform(get("/widget/obs/" + TOKEN + "/events")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void oldLink_redirectsPermanently() throws Exception {
        mvc.perform(get("/widget/twitchat/" + TOKEN))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "/widget/obs/" + TOKEN));
        mvc.perform(get("/widget/twitchat/x"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "/widget/obs/x"));
        verify(apiService, never()).widgetAccess(any());
        verify(apiService, never()).widgetObsConfig(any());
    }
}
