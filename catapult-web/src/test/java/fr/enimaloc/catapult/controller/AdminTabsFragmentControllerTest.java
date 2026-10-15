package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.AdminCclDto;
import fr.enimaloc.catapult.common.dto.admin.AdminCclPage;
import fr.enimaloc.catapult.common.dto.admin.AdminIgdbDescriptorDto;
import fr.enimaloc.catapult.config.I18nConfiguration;
import fr.enimaloc.catapult.config.TwitchatCatalogConfig;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The admin tabs' lazy fragments: rendered for an admin only, from what catapult-api answers. */
@WebMvcTest(controllers = AdminTabsFragmentController.class)
@Import({ModelFiller.class, WebSecurityConfig.class, TwitchatCatalogConfig.class, I18nConfiguration.class})
class AdminTabsFragmentControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;

    private String asAdmin(String url) throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        return mvc.perform(get(url).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void everyFragment_isRefusedToAMember_andToAVisitor() throws Exception {
        for (String url : List.of("/spa/admin/ccl")) {
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
            mvc.perform(get(url).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")).andExpect(status().isForbidden());
        }
        verify(apiService, org.mockito.Mockito.atLeastOnce()).isAdmin();
        verify(apiService, never()).adminCcl();
    }

    @Test
    void ccl_rendersEachCclWithItsMappedDescriptors() throws Exception {
        var violence = new AdminIgdbDescriptorDto(1L, "Violence", 1, "ESRB — Violence");
        var blood = new AdminIgdbDescriptorDto(2L, "Blood", 1, "ESRB — Blood");
        when(apiService.adminCcl()).thenReturn(new AdminCclPage(
                List.of(new AdminCclDto("ViolentGraphic", "Violent", "Graphic violence", List.of(blood, violence))),
                List.of(violence, blood)));

        String html = asAdmin("/spa/admin/ccl");

        assertThat(html).contains("id=\"admin-ccl-refresh\"", "data-ccl-id=\"ViolentGraphic\"", "data-saved=\"1,2\"",
                "Graphic violence", "<mdui-menu-item value=\"1\">ESRB — Violence</mdui-menu-item>");
    }

    @Test
    void ccl_saysSo_whenCatapultApiFails() throws Exception {
        String html = asAdmin("/spa/admin/ccl");

        assertThat(html).contains("data-admin-retry").doesNotContain("data-ccl-id");
    }

    @Test
    void ccl_invitesToRefresh_whenThereIsNone() throws Exception {
        when(apiService.adminCcl()).thenReturn(new AdminCclPage(List.of(), List.of()));

        assertThat(asAdmin("/spa/admin/ccl")).contains("id=\"admin-ccl-refresh\"", "admin-placeholder").doesNotContain("data-ccl-id");
    }
}
