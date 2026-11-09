package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.SocialLinkRequest;
import fr.enimaloc.catapult.common.dto.admin.SocialPlatform;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminSocialLinksController.class)
@Import({WebSecurityConfig.class, ModelFiller.class, SiteSocialLinks.class, LocalInstanceBusConfig.class})
class AdminSocialLinksControllerTest {
    static final String BODY = "{\"platform\":\"GITHUB\",\"label\":\"Code\",\"url\":\"https://github.com/x\",\"visible\":true}";
    static final SocialLinkRequest REQUEST = new SocialLinkRequest(SocialPlatform.GITHUB, "Code", "https://github.com/x", true);
    static final String ORDER = "{\"ids\":[3,1,2]}";

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms twitchatEventForms;
    @MockitoSpyBean SiteSocialLinks siteSocialLinks;

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void createUpdateDeleteOrder_areForwardedForAnAdmin_andEmptyThePagesCopy() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminCreateSocialLink(any())).thenReturn(201);
        when(apiService.adminUpdateSocialLink(anyLong(), any())).thenReturn(200);
        when(apiService.adminDeleteSocialLink(anyLong())).thenReturn(204);
        when(apiService.adminOrderSocialLinks(anyList())).thenReturn(200);

        mvc.perform(admin(post("/admin/social-links"), BODY)).andExpect(status().isCreated());
        mvc.perform(admin(post("/admin/social-links/4"), BODY)).andExpect(status().isOk());
        mvc.perform(admin(post("/admin/social-links/4/delete"), BODY)).andExpect(status().isNoContent());
        mvc.perform(admin(post("/admin/social-links/order"), ORDER)).andExpect(status().isOk());

        verify(apiService).adminCreateSocialLink(REQUEST);
        verify(apiService).adminUpdateSocialLink(4, REQUEST);
        verify(apiService).adminDeleteSocialLink(4);
        verify(apiService).adminOrderSocialLinks(List.of(3L, 1L, 2L));
        verify(siteSocialLinks, times(4)).clear();
    }

    @Test
    void failures_areRelayed_withoutEmptyingThePagesCopy() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminCreateSocialLink(any())).thenReturn(400);
        when(apiService.adminUpdateSocialLink(anyLong(), any())).thenReturn(404);
        when(apiService.adminDeleteSocialLink(anyLong())).thenReturn(502);
        when(apiService.adminOrderSocialLinks(anyList())).thenReturn(400);

        mvc.perform(admin(post("/admin/social-links"), BODY)).andExpect(status().isBadRequest());
        mvc.perform(admin(post("/admin/social-links/4"), BODY)).andExpect(status().isNotFound());
        mvc.perform(admin(post("/admin/social-links/4/delete"), BODY)).andExpect(status().isBadGateway());
        mvc.perform(admin(post("/admin/social-links/order"), ORDER)).andExpect(status().isBadRequest());

        verify(siteSocialLinks, never()).clear();
    }

    @Test
    void aVisitorAndAMember_areRefused_andTheApiIsNeverCalled() throws Exception {
        for (String url : new String[]{"/admin/social-links", "/admin/social-links/4",
                "/admin/social-links/4/delete", "/admin/social-links/order"}) {
            mvc.perform(post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isUnauthorized());
            mvc.perform(admin(post(url), url.endsWith("order") ? ORDER : BODY)).andExpect(status().isForbidden());
        }
        verify(apiService, never()).adminCreateSocialLink(any());
        verify(apiService, never()).adminUpdateSocialLink(anyLong(), any());
        verify(apiService, never()).adminDeleteSocialLink(anyLong());
        verify(apiService, never()).adminOrderSocialLinks(anyList());
        verify(siteSocialLinks, never()).clear();
    }
}
