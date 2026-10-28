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

@WebMvcTest(controllers = AdminGroupsController.class)
@Import({WebSecurityConfig.class, ModelFiller.class})
class AdminGroupsControllerTest {
    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000042");
    static final String BASE = "/admin/groups";

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms twitchatEventForms;

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void create_relaysTheBody_andTheStatus() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminCreateGroup("beta", "Beta", "d")).thenReturn(201);
        when(apiService.adminCreateGroup("dup", "Dup", null)).thenReturn(409);

        mvc.perform(admin(post(BASE), "{\"key\":\"beta\",\"name\":\"Beta\",\"description\":\"d\"}")).andExpect(status().isCreated());
        mvc.perform(admin(post(BASE), "{\"key\":\"dup\",\"name\":\"Dup\"}")).andExpect(status().isConflict());
    }

    @Test
    void rename_deleteAndAddMember_relayTheirStatus() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminRenameGroup(ID, "N", "D")).thenReturn(204);
        when(apiService.adminDeleteGroup(ID)).thenReturn(404);
        when(apiService.adminAddGroupMember(ID, "alice")).thenReturn(404);

        mvc.perform(admin(post(BASE + "/" + ID + "/rename"), "{\"name\":\"N\",\"description\":\"D\"}")).andExpect(status().isNoContent());
        mvc.perform(admin(post(BASE + "/" + ID + "/delete"), "{}")).andExpect(status().isNotFound());
        mvc.perform(admin(post(BASE + "/" + ID + "/members"), "{\"twitchUsername\":\"alice\"}")).andExpect(status().isNotFound());
    }

    @Test
    void addMember_withoutALogin_is400_withoutCallingTheApi() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);

        mvc.perform(admin(post(BASE + "/" + ID + "/members"), "{\"twitchUsername\":\" \"}")).andExpect(status().isBadRequest());
        mvc.perform(admin(post(BASE + "/" + ID + "/members"), "{}")).andExpect(status().isBadRequest());
        verify(apiService, never()).adminAddGroupMember(any(), any());
    }

    @Test
    void unreachable_is502() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminDeleteGroup(ID)).thenReturn(502);

        mvc.perform(admin(post(BASE + "/" + ID + "/delete"), "{}")).andExpect(status().isBadGateway());
    }

    @Test
    void actions_refuseVisitorsAndMembers_andNeedCsrf() throws Exception {
        for (String url : new String[]{BASE, BASE + "/" + ID + "/rename", BASE + "/" + ID + "/delete", BASE + "/" + ID + "/members"}) {
            mvc.perform(post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(admin(post(url), "{}")).andExpect(status().isForbidden());
            mvc.perform(post(url).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        }
        // an admin without a CSRF token is refused too
        when(apiService.isAdmin()).thenReturn(true);
        mvc.perform(post(BASE).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        verify(apiService, never()).adminCreateGroup(any(), any(), any());
        verify(apiService, never()).adminRenameGroup(any(), any(), any());
        verify(apiService, never()).adminDeleteGroup(any());
        verify(apiService, never()).adminAddGroupMember(any(), any());
    }
}
