package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.connect.SteamStartResponse;
import fr.enimaloc.catapult.config.I18nConfiguration;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Locale;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SteamConnectController.class)
@Import({WebSecurityConfig.class, I18nConfiguration.class})
class SteamConnectControllerTest {
    static final String STEAM = "https://steamcommunity.com/openid/login?openid.mode=checkid_setup";

    @Autowired MockMvc mvc;
    @MockitoBean ApiClient apiClient;

    @Test
    void connect_goesToTheSteamLoginCatapultApiPrepared() throws Exception {
        when(apiClient.get("/api/connect/steam/start", SteamStartResponse.class)).thenReturn(new SteamStartResponse(STEAM));

        mvc.perform(get("/connect/steam").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(redirectedUrl(STEAM));
    }

    @Test
    void connect_neverFollowsAnythingButSteamsLogin() throws Exception {
        when(apiClient.get("/api/connect/steam/start", SteamStartResponse.class))
                .thenReturn(new SteamStartResponse("https://evil.example/openid/login?x"));

        mvc.perform(get("/connect/steam").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(redirectedUrl("/connect/steam/close?error=unavailable"));
    }

    @Test
    void connect_endsOnTheClosingPage_whenCatapultApiFails_orLoggedOut() throws Exception {
        mvc.perform(get("/connect/steam").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(redirectedUrl("/connect/steam/close?error=unavailable"));
        mvc.perform(get("/connect/steam")).andExpect(redirectedUrl("/connect/steam/close?error=session"));
        // The logged-out visitor's never reached catapult-api: only the first call did.
        verify(apiClient, times(1)).get(eq("/api/connect/steam/start"), eq(SteamStartResponse.class));
    }

    @Test
    void close_closesThePopupOnceLinked_andTellsAFailure() throws Exception {
        mvc.perform(get("/connect/steam/close").locale(Locale.ENGLISH))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Steam account linked")))
                .andExpect(content().string(containsString("window.close();")));
        mvc.perform(get("/connect/steam/close").param("error", "verify").locale(Locale.FRENCH))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("a échoué")))
                .andExpect(content().string(not(containsString("<script>"))));
    }

    @Test
    void disconnect_relaysCatapultApisStatus() throws Exception {
        when(apiClient.call(eq(HttpMethod.POST), eq("/api/connect/steam/disconnect"), isNull(), eq(Void.class)))
                .thenReturn(new ApiClient.Result<>(204, null), new ApiClient.Result<>(404, null), new ApiClient.Result<>(502, null));

        for (int expected : new int[]{204, 404, 502}) {
            mvc.perform(post("/connect/steam/disconnect").with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                    .andExpect(status().is(expected));
        }
    }

    @Test
    void disconnect_needsASessionAndTheCsrfToken() throws Exception {
        mvc.perform(post("/connect/steam/disconnect").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post("/connect/steam/disconnect").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isForbidden());
        verify(apiClient, never()).call(any(), any(), any(), any());
    }
}
