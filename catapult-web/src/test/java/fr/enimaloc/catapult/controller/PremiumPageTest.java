package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.premium.PremiumOfferDto;
import fr.enimaloc.catapult.common.dto.premium.PremiumStatusDto;
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

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** The premium page: the member's status, what each tier gets, and its 401 / unavailable states. */
@WebMvcTest(controllers = {IndexController.class, SpaFragmentController.class})
@Import({ModelFiller.class, SiteMetaTags.class, LocalInstanceBusConfig.class, WebSecurityConfig.class, TwitchatCatalogConfig.class, I18nConfiguration.class})
class PremiumPageTest {

    private static final PremiumOfferDto OFFER = new PremiumOfferDto(60, 30, 90, 60, 5);

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean NavEventStreamController navEvents;

    private String fragment(PremiumStatusDto status) throws Exception {
        when(apiService.premiumStatus()).thenReturn(status);
        return mvc.perform(get("/spa/premium").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/premium :: premium"))
                .andExpect(model().attribute("premium", status))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void fragment_showsAFreeAccount_withTheTiersCompared() throws Exception {
        String html = fragment(new PremiumStatusDto(false, null, 3, 5, OFFER));

        assertThat(html).contains("id=\"premium-page\"", "data-premium-status=\"free\"",
                        "1 min", "30 s", "1 min 30 s", "id=\"premium-obtain\"")
                .doesNotContain("data-premium-usage");
    }

    @Test
    void fragment_showsAPremiumAccount_withItsEndDateAndUsage() throws Exception {
        String html = fragment(new PremiumStatusDto(true, LocalDate.of(2026, 12, 31), 7, null, OFFER));

        assertThat(html).contains("data-premium-status=\"premium\"", "Premium until 12/31/2026", "data-premium-usage");
    }

    @Test
    void fragment_formatsTheEndDateAndDurationsInFrench() throws Exception {
        when(apiService.premiumStatus()).thenReturn(new PremiumStatusDto(true, LocalDate.of(2026, 12, 31), 7, null, OFFER));

        String html = mvc.perform(get("/spa/premium").header("Accept-Language", "fr").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Premium jusqu&#39;au 31/12/2026", "1 min 30</td>", "Bientôt disponible");
    }

    @Test
    void fragment_showsAPremiumAccountWithoutEnd_withoutADate() throws Exception {
        String html = fragment(new PremiumStatusDto(true, null, 7, null, OFFER));

        assertThat(html).contains("data-premium-status=\"premium\"").doesNotContain("data-premium-until");
    }

    @Test
    void page_isServedFullAndAsAFragment() throws Exception {
        when(apiService.premiumStatus()).thenReturn(new PremiumStatusDto(false, null, 3, 5, OFFER));

        String html = mvc.perform(get("/premium").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"))
                .andExpect(model().attribute("page", "premium"))
                .andExpect(model().attribute("error", false))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("id=\"premium-page\"");
    }

    @Test
    void page_refusesAVisitorWhoIsLoggedOut() throws Exception {
        mvc.perform(get("/premium"))
                .andExpect(status().isUnauthorized())
                .andExpect(view().name("index"))
                .andExpect(model().attribute("error", true));
        mvc.perform(get("/spa/premium"))
                .andExpect(status().isUnauthorized())
                .andExpect(view().name("pages/error :: error"));
        verify(apiService, never()).premiumStatus();
    }

    @Test
    void page_saysSo_whenCatapultApiFails() throws Exception {
        when(apiService.premiumStatus()).thenReturn(null);

        for (String url : List.of("/premium", "/spa/premium")) {
            String html = mvc.perform(get(url).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(html).contains("id=\"premium-page-unavailable\"").doesNotContain("id=\"premium-page\"");
        }
    }
}
