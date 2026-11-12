package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.DtddRefetchJobDto;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminDtddCachesController.class)
@Import({WebSecurityConfig.class, ModelFiller.class})
class AdminDtddCachesControllerTest {
    static final String BASE = "/admin/dtdd-caches";
    static final DtddRefetchJobDto RUNNING = new DtddRefetchJobDto("RUNNING", 9, 0, 0, null, null, null, null);

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms twitchatEventForms;

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder b) {
        return b.with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt");
    }

    @Test
    void refetch_forwardsTheKind_andTheKeyAsItIs_andRelaysTheStatus() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminRefetchDtddCache("searches", "halo infinite")).thenReturn(200);
        when(apiService.adminRefetchDtddCache("topics", "7")).thenReturn(502);

        mvc.perform(admin(post(BASE + "/searches/refetch").param("key", "halo infinite"))).andExpect(status().isOk());
        mvc.perform(admin(post(BASE + "/topics/refetch").param("key", "7"))).andExpect(status().isBadGateway());
    }

    @Test
    void theBulkRefetch_isStartedAndCancelled_withCatapultApisAnswer() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminStartDtddRefetch()).thenReturn(new ApiClient.Result<>(202, RUNNING));
        when(apiService.adminCancelDtddRefetch()).thenReturn(new ApiClient.Result<>(200, RUNNING));

        mvc.perform(admin(post(BASE + "/refetch-all/start"))).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("RUNNING")).andExpect(jsonPath("$.total").value(9));
        mvc.perform(admin(post(BASE + "/refetch-all/cancel"))).andExpect(status().isOk());

        when(apiService.adminStartDtddRefetch()).thenReturn(new ApiClient.Result<>(409, null));
        mvc.perform(admin(post(BASE + "/refetch-all/start"))).andExpect(status().isConflict());
    }

    @Test
    void everyRoute_refusesVisitorsAndMembers_andNeedsCsrf() throws Exception {
        List<String> routes = List.of(BASE + "/topics/refetch?key=7", BASE + "/refetch-all/start",
                BASE + "/refetch-all/cancel");
        for (String route : routes) {
            mvc.perform(post(route).with(csrf())).andExpect(status().isUnauthorized());
            mvc.perform(admin(post(route))).andExpect(status().isForbidden());
        }
        when(apiService.isAdmin()).thenReturn(true);
        for (String route : routes) {
            mvc.perform(post(route).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")).andExpect(status().isForbidden());
        }
        verify(apiService, never()).adminRefetchDtddCache(any(), any());
        verify(apiService, never()).adminStartDtddRefetch();
        verify(apiService, never()).adminCancelDtddRefetch();
    }
}
