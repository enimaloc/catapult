package fr.enimaloc.catapult.apidocs;

import fr.enimaloc.catapult.config.I18nConfiguration;
import fr.enimaloc.catapult.config.TwitchatCatalogConfig;
import fr.enimaloc.catapult.controller.LocalInstanceBusConfig;
import fr.enimaloc.catapult.controller.ModelFiller;
import fr.enimaloc.catapult.controller.NavEventStreamController;
import fr.enimaloc.catapult.controller.SiteMetaTags;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiHealthProbe;
import fr.enimaloc.catapult.service.ApiService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(controllers = ApiDocsController.class)
@Import({ModelFiller.class, SiteMetaTags.class, LocalInstanceBusConfig.class, WebSecurityConfig.class,
        TwitchatCatalogConfig.class, I18nConfiguration.class, ApiDocsAssets.class})
class ApiDocsControllerTest {
    static final String SPEC = "{\"openapi\":\"3.1.0\",\"servers\":[{\"url\":\"/\"}],\"paths\":{}}";
    static final Instant FETCHED_AT = Instant.parse("2026-10-27T09:30:00Z");

    @Autowired MockMvc mvc;
    @MockitoBean ApiDocsSpec spec;
    @MockitoBean ApiHealthProbe probe;
    @MockitoBean ApiService apiService;
    @MockitoBean NavEventStreamController navEvents;

    String page(String path, Locale locale) throws Exception {
        return mvc.perform(get(path).locale(locale)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void servesThePage_fullAndAsAFragment_withSwaggerUi() throws Exception {
        when(spec.current()).thenReturn(Optional.of(new ApiDocsSpec.Cached(SPEC, FETCHED_AT, false)));

        mvc.perform(get("/docs/api")).andExpect(view().name("index"));
        mvc.perform(get("/spa/docs/api")).andExpect(view().name("pages/api-docs :: api-docs"));
        String full = page("/docs/api", Locale.ENGLISH);
        String fragment = page("/spa/docs/api", Locale.ENGLISH);

        for (String html : List.of(full, fragment)) {
            assertThat(html).contains("id=\"swagger-ui\"", "data-spec=\"/docs/api/openapi.json\"",
                            "/_static/swagger-ui/5.34.0/swagger-ui-bundle.js", "/_static/swagger-ui/5.34.0/swagger-ui.css")
                    .contains("data-state=\"unknown\"", "Checking the API…", "data-label-up=\"API up\"")
                    .doesNotContain("id=\"api-docs-cached\"", "id=\"api-docs-unavailable\"");
        }
        assertThat(full).contains("<title>API documentation - ", "rel=\"canonical\"", "src=\"/js/api-docs.js\"");
        // The page asks /api/health itself: rendering it never waits on the probe.
        verifyNoInteractions(probe);
    }

    @Test
    void tellsTheCopyIsCached_andFromWhen() throws Exception {
        when(spec.current()).thenReturn(Optional.of(new ApiDocsSpec.Cached(SPEC, FETCHED_AT, true)));
        String when = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault()).format(FETCHED_AT);

        String french = page("/docs/api", Locale.FRENCH);
        String english = page("/spa/docs/api", Locale.ENGLISH);

        assertThat(french).contains("id=\"api-docs-cached\"", "en cache, du " + when, "id=\"swagger-ui\"")
                .contains("data-fetched-at=\"2026-10-27T09:30:00Z\"", "data-label=\"API injoignable : documentation en cache, du {0}.\"")
                .contains("data-state=\"unknown\"", "Vérification de l&#39;API…");
        assertThat(english).contains("id=\"api-docs-cached\"", "cached, from " + when);
    }

    @Test
    void saysSoClearly_with200_whenThereIsNoSpec() throws Exception {
        when(spec.current()).thenReturn(Optional.empty());

        String french = page("/docs/api", Locale.FRENCH);
        String english = page("/spa/docs/api", Locale.ENGLISH);

        assertThat(french).contains("id=\"api-docs-unavailable\"").doesNotContain("id=\"swagger-ui\"");
        assertThat(english).contains("id=\"api-docs-unavailable\"", "be shown right now: the API")
                .doesNotContain("id=\"swagger-ui\"");
    }

    @Test
    void servesTheSpec() throws Exception {
        when(spec.current()).thenReturn(Optional.of(new ApiDocsSpec.Cached(SPEC, FETCHED_AT, true)));

        mvc.perform(get("/docs/api/openapi.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(content().string(SPEC));
    }

    @Test
    void answers503WithoutASpec() throws Exception {
        when(spec.current()).thenReturn(Optional.empty());

        mvc.perform(get("/docs/api/openapi.json"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "60"));
    }

    @Test
    void redirectsCatapultApisSwaggerUiToThePage() throws Exception {
        for (String path : List.of("/swagger-ui.html", "/swagger-ui/index.html")) {
            mvc.perform(get(path)).andExpect(status().isFound()).andExpect(header().string("Location", "/docs/api"));
        }
    }

    @Test
    void servesSwaggerUisAssetsFromTheSite() throws Exception {
        for (String asset : List.of("swagger-ui-bundle.js", "swagger-ui.css")) {
            mvc.perform(get("/_static/swagger-ui/5.34.0/" + asset)).andExpect(status().isOk());
        }
    }
}
