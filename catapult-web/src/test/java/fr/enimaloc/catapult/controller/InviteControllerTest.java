package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.InvitePageData;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InviteController.class)
@Import({WebSecurityConfig.class, ModelFiller.class})
class InviteControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms twitchatEventForms;

    private static MockHttpSession member() {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"username\":\"alice\"}".getBytes());
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ApiClient.SESSION_JWT_KEY, "h." + payload + ".s");
        return session;
    }

    @Test
    void invite_redirectsToTheOwnChannelTab_visitorGets401() throws Exception {
        mvc.perform(get("/invite").session(member()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/channel/alice#invitations"));

        mvc.perform(get("/invite")).andExpect(status().isUnauthorized());
    }

    @Test
    void invite_withATokenWithoutLogin_gets401() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":\"1\"}".getBytes());
        session.setAttribute(ApiClient.SESSION_JWT_KEY, "h." + payload + ".s");

        mvc.perform(get("/invite").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void regenerate_needsLoginAndCsrf() throws Exception {
        when(apiService.regenerateInvite()).thenReturn(new ApiClient.Result<>(204, null));

        mvc.perform(post("/invite/regenerate").session(member()).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(post("/invite/regenerate").session(member()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/invite/regenerate").with(csrf()))
                .andExpect(status().isUnauthorized());

        verify(apiService, org.mockito.Mockito.times(1)).regenerateInvite();
    }

    @Test
    void regenerate_relaysCatapultApisRefusal() throws Exception {
        when(apiService.regenerateInvite()).thenReturn(new ApiClient.Result<>(403, null));

        mvc.perform(post("/invite/regenerate").session(member()).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void regenerate_answersTheNewCodeAndLink() throws Exception {
        when(apiService.regenerateInvite()).thenReturn(new ApiClient.Result<>(200, new InvitePageData(true,
                java.util.UUID.randomUUID(), "NEWCODE2", "https://catapult.example/join?invite=NEWCODE2", null,
                java.util.List.of(new fr.enimaloc.catapult.common.dto.InviteRedemptionDto("friend", null)))));

        mvc.perform(post("/invite/regenerate").session(member()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"code\":\"NEWCODE2\","
                        + "\"inviteUrl\":\"https://catapult.example/join?invite=NEWCODE2\"}", org.springframework.test.json.JsonCompareMode.STRICT));
    }

    @Test
    void regenerate_withoutAnInviteAnymore_answersNoContent() throws Exception {
        when(apiService.regenerateInvite()).thenReturn(new ApiClient.Result<>(200,
                new InvitePageData(false, null, null, null, null, java.util.List.of())));

        mvc.perform(post("/invite/regenerate").session(member()).with(csrf()))
                .andExpect(status().isNoContent());
    }
}
