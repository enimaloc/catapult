package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.HelpContent;
import fr.enimaloc.catapult.config.I18nConfiguration;
import fr.enimaloc.catapult.config.TwitchatCatalogConfig;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(controllers = HelpPageController.class)
@Import({ModelFiller.class, SiteMetaTags.class, WebSecurityConfig.class, TwitchatCatalogConfig.class, I18nConfiguration.class})
class HelpPageControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean NavEventStreamController navEvents;

    @Test
    void showsACardsHelp_fullAndAsAFragment_inTheVisitorsLanguage() throws Exception {
        when(apiService.help(eq("status"), any())).thenReturn(new HelpContent("Bot status", "<p>The <b>bot</b> runs.</p>"));

        String full = mvc.perform(get("/help/status").locale(Locale.FRENCH)).andExpect(status().isOk())
                .andExpect(view().name("index")).andReturn().getResponse().getContentAsString();
        String fragment = mvc.perform(get("/spa/help-page/status")).andExpect(status().isOk())
                .andExpect(view().name("pages/help :: help")).andReturn().getResponse().getContentAsString();

        for (String html : java.util.List.of(full, fragment)) {
            assertThat(html).contains("<h1>Bot status</h1>").contains("<p>The <b>bot</b> runs.</p>");
        }
        assertThat(full).contains("<title>Aide - ").contains("name=\"robots\" content=\"noindex\"");
        verify(apiService).help("status", Locale.FRENCH);
    }

    @Test
    void anUnknownCard_isANotFound() throws Exception {
        mvc.perform(get("/help/nope")).andExpect(status().isNotFound()).andExpect(view().name("index"));
        mvc.perform(get("/spa/help-page/nope")).andExpect(status().isNotFound()).andExpect(view().name("pages/error :: error"));
    }

    @Test
    void aMalformedCardId_neverReachesCatapultApi() throws Exception {
        mvc.perform(get("/help/Status1")).andExpect(status().isNotFound());
        mvc.perform(get("/spa/help-page/a_b")).andExpect(status().isNotFound());
        verify(apiService, never()).help(anyString(), any());
    }

    @Test
    void helpWithoutACard_isANotFound() throws Exception {
        mvc.perform(get("/help")).andExpect(status().isNotFound()).andExpect(view().name("index"));
        mvc.perform(get("/spa/help-page")).andExpect(status().isNotFound()).andExpect(view().name("pages/error :: error"));
    }
}
