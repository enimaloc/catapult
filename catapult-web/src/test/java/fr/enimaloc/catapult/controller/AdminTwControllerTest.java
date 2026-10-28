package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.TwRebuild;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminTwController.class)
@Import({WebSecurityConfig.class, ModelFiller.class})
class AdminTwControllerTest {
    static final String BASE = "/admin/tw";

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms twitchatEventForms;

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void create_relaysTheDefinition_andTheStatus() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminCreateTw("gore", "Gore", 3)).thenReturn(201);
        when(apiService.adminCreateTw("dup", "Dup", 0)).thenReturn(409);

        mvc.perform(admin(post(BASE), "{\"id\":\"gore\",\"label\":\" Gore \",\"sortOrder\":3}")).andExpect(status().isCreated());
        mvc.perform(admin(post(BASE), "{\"id\":\"dup\",\"label\":\"Dup\"}")).andExpect(status().isConflict());
    }

    @Test
    void create_checksIdAndLabel() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);

        for (String body : new String[]{
                "{\"id\":\"Bad Id\",\"label\":\"L\"}", "{\"id\":\"\",\"label\":\"L\"}", "{\"label\":\"L\"}",
                "{\"id\":\"" + "a".repeat(41) + "\",\"label\":\"L\"}",
                "{\"id\":\"ok\",\"label\":\"" + "x".repeat(81) + "\"}", "{\"id\":\"ok\",\"label\":\"  \"}", "{\"id\":\"ok\"}"}) {
            mvc.perform(admin(post(BASE), body)).andExpect(status().isBadRequest());
        }
        verify(apiService, never()).adminCreateTw(any(), any(), anyInt());
    }

    @Test
    void create_aTakenId_is409_withoutCallingTheApi_andAnUnreadableListIsNotAnObstacle() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminTws()).thenReturn(java.util.List.of(
                new fr.enimaloc.catapult.common.dto.admin.TwDefinitionDto("gore", "Gore", null, true, 0)));

        mvc.perform(admin(post(BASE), "{\"id\":\"gore\",\"label\":\"Again\"}")).andExpect(status().isConflict());
        verify(apiService, never()).adminCreateTw(any(), any(), anyInt());

        when(apiService.adminTws()).thenReturn(null);
        when(apiService.adminCreateTw("gore", "Again", 0)).thenReturn(201);
        mvc.perform(admin(post(BASE), "{\"id\":\"gore\",\"label\":\"Again\"}")).andExpect(status().isCreated());
    }

    @Test
    void rebuild_relaysEachMode_andItsStatus() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminRebuildTws(TwRebuild.NON_OVERRIDDEN)).thenReturn(202);
        when(apiService.adminRebuildTws(TwRebuild.UNTOUCHED)).thenReturn(503);
        when(apiService.adminRebuildTws(TwRebuild.FORCE_PINNED)).thenReturn(502);

        mvc.perform(admin(post(BASE + "/rebuild/non-overridden"), "{}")).andExpect(status().isAccepted());
        mvc.perform(admin(post(BASE + "/rebuild/untouched"), "{}")).andExpect(status().isServiceUnavailable());
        mvc.perform(admin(post(BASE + "/rebuild/force-pinned"), "{}")).andExpect(status().isBadGateway());
    }

    @Test
    void rebuild_unknownMode_is404() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);

        mvc.perform(admin(post(BASE + "/rebuild/everything"), "{}")).andExpect(status().isNotFound());
        verify(apiService, never()).adminRebuildTws(any());
    }

    @Test
    void actions_refuseVisitorsAndMembers_andNeedCsrf() throws Exception {
        for (String url : new String[]{BASE, BASE + "/rebuild/untouched", BASE + "/rebuild/force-pinned"}) {
            mvc.perform(post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(admin(post(url), "{}")).andExpect(status().isForbidden());
        }
        // an admin without a CSRF token is refused too
        when(apiService.isAdmin()).thenReturn(true);
        for (String url : new String[]{BASE, BASE + "/rebuild/untouched"}) {
            mvc.perform(post(url).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"ok\",\"label\":\"L\"}")).andExpect(status().isForbidden());
        }
        verify(apiService, never()).adminCreateTw(any(), any(), anyInt());
        verify(apiService, never()).adminRebuildTws(any());
    }

    @Test
    void keywords_addRelaysTheStatus_andChecksTheLength() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminAddTwKeyword("gore", "blood")).thenReturn(204);
        when(apiService.adminAddTwKeyword("gore", "x".repeat(50))).thenReturn(404);

        mvc.perform(admin(post(BASE + "/gore/keywords"), "{\"keyword\":\" blood \"}")).andExpect(status().isNoContent());
        mvc.perform(admin(post(BASE + "/gore/keywords"), "{\"keyword\":\"" + "x".repeat(50) + "\"}")).andExpect(status().isNotFound());
        for (String body : new String[]{"{\"keyword\":\"a\"}", "{\"keyword\":\" a \"}", "{\"keyword\":\"" + "x".repeat(51) + "\"}", "{}"}) {
            mvc.perform(admin(post(BASE + "/gore/keywords"), body)).andExpect(status().isBadRequest());
        }
        verify(apiService, org.mockito.Mockito.times(2)).adminAddTwKeyword(any(), any());
    }

    @Test
    void keywords_removeTakesTheKeywordFromTheBody() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminRemoveTwKeyword("gore", "gore/blood é")).thenReturn(204);

        mvc.perform(admin(post(BASE + "/gore/keywords/remove"), "{\"keyword\":\"gore/blood é\"}")).andExpect(status().isNoContent());
        mvc.perform(admin(post(BASE + "/gore/keywords/remove"), "{\"keyword\":\"  \"}")).andExpect(status().isBadRequest());
        verify(apiService, org.mockito.Mockito.times(1)).adminRemoveTwKeyword(any(), any());
    }

    @Test
    void keywords_areGuardedAndNeedCsrf() throws Exception {
        for (String url : new String[]{"/gore/keywords", "/gore/keywords/remove", "/gore/keywords/test"}) {
            mvc.perform(post(BASE + url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post(BASE + url).with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        }
        when(apiService.isAdmin()).thenReturn(true);
        mvc.perform(post(BASE + "/gore/keywords").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                .contentType(MediaType.APPLICATION_JSON).content("{\"keyword\":\"blood\"}")).andExpect(status().isForbidden());
        verify(apiService, never()).adminAddTwKeyword(any(), any());
        verify(apiService, never()).adminRemoveTwKeyword(any(), any());
        verify(apiService, never()).adminTestTwKeyword(any(), any(), any());
    }

    @Test
    void test_relaysTheResult_or502_andChecksTheAppIdAndTheDraft() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminTestTwKeyword("gore", "730", "gor")).thenReturn(new fr.enimaloc.catapult.common.dto.admin.TwKeywordTestDto(
                java.util.List.of("gore"), java.util.List.of(1), true, "gore"));
        when(apiService.adminTestTwKeyword("gore", "731", null)).thenReturn(null);

        mvc.perform(admin(post(BASE + "/gore/keywords/test"), "{\"appId\":\"730\",\"draftKeyword\":\"gor\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.matchedKeywords[0]").value("gore"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.draftKeywordMatch").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.notes").value("gore"));
        mvc.perform(admin(post(BASE + "/gore/keywords/test"), "{\"appId\":\"731\"}")).andExpect(status().isBadGateway());
        for (String body : new String[]{"{\"appId\":\"abc\"}", "{\"appId\":\"\"}", "{}", "{\"appId\":\"730\",\"draftKeyword\":\"" + "x".repeat(51) + "\"}"}) {
            mvc.perform(admin(post(BASE + "/gore/keywords/test"), body)).andExpect(status().isBadRequest());
        }
        verify(apiService, org.mockito.Mockito.times(2)).adminTestTwKeyword(any(), any(), any());
    }
}
