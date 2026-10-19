package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.MetaTagRequest;
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

import static fr.enimaloc.catapult.common.dto.admin.MetaTagAttribute.PROPERTY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminMetaTagsController.class)
@Import({WebSecurityConfig.class, ModelFiller.class, SiteMetaTags.class})
class AdminMetaTagsControllerTest {
    static final String BODY = "{\"attribute\":\"PROPERTY\",\"key\":\"og:image\",\"content\":\"https://x/y.png\"}";
    static final MetaTagRequest REQUEST = new MetaTagRequest(PROPERTY, "og:image", "https://x/y.png");

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms twitchatEventForms;
    @MockitoSpyBean SiteMetaTags siteMetaTags;

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder admin(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b) {
        return b.with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                .contentType(MediaType.APPLICATION_JSON).content(BODY);
    }

    @Test
    void createUpdateDelete_areForwardedForAnAdmin_andEmptyThePagesCopy() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminCreateMetaTag(any())).thenReturn(201);
        when(apiService.adminUpdateMetaTag(anyLong(), any())).thenReturn(200);
        when(apiService.adminDeleteMetaTag(anyLong())).thenReturn(204);

        mvc.perform(admin(post("/admin/meta/tags"))).andExpect(status().isCreated());
        mvc.perform(admin(post("/admin/meta/tags/4"))).andExpect(status().isOk());
        mvc.perform(admin(post("/admin/meta/tags/4/delete"))).andExpect(status().isNoContent());

        verify(apiService).adminCreateMetaTag(REQUEST);
        verify(apiService).adminUpdateMetaTag(4, REQUEST);
        verify(apiService).adminDeleteMetaTag(4);
        verify(siteMetaTags, times(3)).clear();
    }

    @Test
    void failures_areRelayed_withoutEmptyingThePagesCopy() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminCreateMetaTag(any())).thenReturn(409);
        when(apiService.adminUpdateMetaTag(anyLong(), any())).thenReturn(400);
        when(apiService.adminDeleteMetaTag(anyLong())).thenReturn(502);

        mvc.perform(admin(post("/admin/meta/tags"))).andExpect(status().isConflict());
        mvc.perform(admin(post("/admin/meta/tags/4"))).andExpect(status().isBadRequest());
        mvc.perform(admin(post("/admin/meta/tags/4/delete"))).andExpect(status().isBadGateway());

        verify(siteMetaTags, never()).clear();
    }

    @Test
    void aVisitorAndAMember_areRefused_andTheApiIsNeverCalled() throws Exception {
        for (String url : new String[]{"/admin/meta/tags", "/admin/meta/tags/4", "/admin/meta/tags/4/delete"}) {
            mvc.perform(post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isUnauthorized());
            mvc.perform(admin(post(url))).andExpect(status().isForbidden());
        }
        verify(apiService, never()).adminCreateMetaTag(any());
        verify(apiService, never()).adminUpdateMetaTag(anyLong(), any());
        verify(apiService, never()).adminDeleteMetaTag(anyLong());
        verify(siteMetaTags, never()).clear();
    }
}
