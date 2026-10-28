package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminDtddMappingController.class)
@Import({WebSecurityConfig.class, ModelFiller.class})
class AdminDtddMappingControllerTest {
    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000042");
    static final String BASE = "/admin/dtdd-mapping/proposals/" + ID;

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms twitchatEventForms;

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void approve_isForwardedForAnAdmin_andItsStatusRelayed() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminApproveDtddProposal(ID)).thenReturn(204);

        mvc.perform(admin(post(BASE + "/approve"), "{}")).andExpect(status().isNoContent());
    }

    @Test
    void approve_relays409() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminApproveDtddProposal(ID)).thenReturn(409);

        mvc.perform(admin(post(BASE + "/approve"), "{}")).andExpect(status().isConflict());
    }

    @Test
    void reject_forwardsTheReason_andRelaysTheStatus() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminRejectDtddProposal(ID, "dup")).thenReturn(204);
        when(apiService.adminRejectDtddProposal(ID, null)).thenReturn(404);

        mvc.perform(admin(post(BASE + "/reject"), "{\"reason\":\"dup\"}")).andExpect(status().isNoContent());
        mvc.perform(admin(post(BASE + "/reject"), "{}")).andExpect(status().isNotFound());
    }

    @Test
    void unreachable_is502() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminApproveDtddProposal(ID)).thenReturn(502);

        mvc.perform(admin(post(BASE + "/approve"), "{}")).andExpect(status().isBadGateway());
    }

    @Test
    void actions_refuseVisitorsAndMembers_andNeedCsrf() throws Exception {
        for (String url : new String[]{BASE + "/approve", BASE + "/reject"}) {
            mvc.perform(post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(admin(post(url), "{}")).andExpect(status().isForbidden());
            mvc.perform(post(url).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        }
        verify(apiService, never()).adminApproveDtddProposal(any());
        verify(apiService, never()).adminRejectDtddProposal(any(), any());
    }
}
