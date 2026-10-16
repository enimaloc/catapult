package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.WellKnownContent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static fr.enimaloc.catapult.common.dto.admin.WellKnownLocation.ROOT;
import static fr.enimaloc.catapult.common.dto.admin.WellKnownLocation.WELL_KNOWN;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {WellKnownServeController.class, IndexController.class, SeoController.class})
@Import({WebSecurityConfig.class, ModelFiller.class, WellKnownCache.class,
        fr.enimaloc.catapult.config.TwitchatCatalogConfig.class, fr.enimaloc.catapult.config.I18nConfiguration.class})
class WellKnownServeControllerTest {
    @org.junit.jupiter.api.BeforeEach void reset() { cache.clear(); }

    @Autowired MockMvc mvc;
    @Autowired WellKnownCache cache;
    @MockitoBean ApiService apiService;
    @MockitoBean fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms twitchatEventForms;

    static WellKnownContent ok(String type, String body) {
        return new WellKnownContent(200, type, body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void servesAWellKnownFile_multiSegment_withItsTypeAndNosniff() throws Exception {
        when(apiService.wellKnown(WELL_KNOWN, "acme-challenge/abc")).thenReturn(ok("text/plain;charset=UTF-8", "token"));
        mvc.perform(get("/.well-known/acme-challenge/abc"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/plain;charset=UTF-8"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().string("token"));
    }

    @Test
    void servesARootFile() throws Exception {
        when(apiService.wellKnown(ROOT, "riot.txt")).thenReturn(ok("text/plain", "riot-code"));
        mvc.perform(get("/riot.txt")).andExpect(status().isOk()).andExpect(content().string("riot-code"));
    }

    @Test
    void relays404And502() throws Exception {
        when(apiService.wellKnown(WELL_KNOWN, "nope.txt")).thenReturn(new WellKnownContent(404, null, null));
        when(apiService.wellKnown(WELL_KNOWN, "down.txt")).thenReturn(new WellKnownContent(502, null, null));
        mvc.perform(get("/.well-known/nope.txt")).andExpect(status().isNotFound());
        mvc.perform(get("/.well-known/down.txt")).andExpect(status().isBadGateway());
    }

    @Test
    void neverAsksForATraversal() throws Exception {
        // Spring Security's StrictHttpFirewall may refuse it first (400): any 4xx, never a file.
        mvc.perform(get("/.well-known/../application.properties")).andExpect(status().is4xxClientError());
        mvc.perform(get("/.well-known/a/%2e%2e/b")).andExpect(status().is4xxClientError());
        verify(apiService, never()).wellKnown(any(), argThat(p -> p.contains("..")));
    }

    @Test
    void cachesAnswersUntilCleared() throws Exception {
        when(apiService.wellKnown(WELL_KNOWN, "security.txt")).thenReturn(ok("text/plain", "v1"));
        mvc.perform(get("/.well-known/security.txt")).andExpect(content().string("v1"));
        when(apiService.wellKnown(WELL_KNOWN, "security.txt")).thenReturn(ok("text/plain", "v2"));
        mvc.perform(get("/.well-known/security.txt")).andExpect(content().string("v1"));
        cache.clear();
        mvc.perform(get("/.well-known/security.txt")).andExpect(content().string("v2"));
    }

    @Test
    void doesNotCacheAnUnreachableApi() throws Exception {
        when(apiService.wellKnown(WELL_KNOWN, "x.txt")).thenReturn(new WellKnownContent(502, null, null), ok("text/plain", "x"));
        mvc.perform(get("/.well-known/x.txt")).andExpect(status().isBadGateway());
        mvc.perform(get("/.well-known/x.txt")).andExpect(status().isOk());
    }

    @Test
    void pagesAndSeoRoutesKeepTheirs() throws Exception {
        mvc.perform(get("/robots.txt")).andExpect(status().isOk());
        mvc.perform(get("/sitemap.xml")).andExpect(status().isOk());
        mvc.perform(get("/privacy")).andExpect(status().isOk());
        mvc.perform(get("/no-such-page")).andExpect(status().isNotFound());
        verify(apiService, never()).wellKnown(any(), any());
    }
}
