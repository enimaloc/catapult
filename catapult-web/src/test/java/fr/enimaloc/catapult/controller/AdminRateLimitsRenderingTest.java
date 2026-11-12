package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.LearnedRateLimitDto;
import fr.enimaloc.catapult.config.I18nConfiguration;
import fr.enimaloc.catapult.config.TwitchatCatalogConfig;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The learned rate limits as the admin Keys tab renders them: each host, then its routes, learned or corrected. */
@WebMvcTest(controllers = AdminTabsFragmentController.class)
@Import({ModelFiller.class, WebSecurityConfig.class, TwitchatCatalogConfig.class, I18nConfiguration.class})
class AdminRateLimitsRenderingTest {

    private static final Instant AT = Instant.parse("2026-11-12T10:00:00Z");
    private static final String STORE = "store.steampowered.com";

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean BroadcastState broadcastState;

    @BeforeEach
    void anAdmin() {
        when(apiService.isAdmin()).thenReturn(true);
    }

    private String render(Locale locale) throws Exception {
        return mvc.perform(get("/spa/admin/keys").locale(locale).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void eachHost_thenItsRoutes_withWhatWasLearnedOrCorrected() throws Exception {
        when(apiService.adminRateLimits()).thenReturn(List.of(
                new LearnedRateLimitDto("HOST " + STORE, STORE, null, null, null, false, 190, 300, false, 76_800,
                        false, 4, AT, 23, null),
                new LearnedRateLimitDto("GET " + STORE + "/app/{id}", STORE, "GET", "/app/{id}", "ROUTE", true, 12,
                        60, true, 30_000, true, 1, AT, 12, AT.plusSeconds(25)),
                new LearnedRateLimitDto("GET " + STORE + "/api/appdetails", STORE, "GET", "/api/appdetails", "HOST",
                        false, null, null, false, 60_000, false, 0, null, 3, null),
                new LearnedRateLimitDto("HOST odd.example", "odd.example", null, null, null, false, 7, 42, true,
                        60_000, false, 0, null, 0, null)));

        String html = render(Locale.FRENCH);

        assertThat(html).contains("data-admin-key=\"HOST store.steampowered.com\"", "admin-rl-host", "admin-rl-route",
                "190 par 5 min", "76,8 s", "12 par minute", "Limite propre", "choisi par un admin", "corrigé",
                "en attente jusqu", "Budget de l&#39;hôte", "aucune connue", "7 par 42 s", "id=\"admin-rl-dialog\"",
                "data-calls=\"12\"", "data-window=\"60\"", "data-resume=\"30\"", "data-scope-pinned=\"true\"");
        assertThat(render(Locale.ENGLISH)).contains("190 per 5 min", "Its own limit");
    }

    @Test
    void nothingLearned_orCatapultApiDown_isTold() throws Exception {
        when(apiService.adminRateLimits()).thenReturn(List.of());
        String none = render(Locale.FRENCH);
        assertThat(none).contains("Aucune plateforme n&#39;a encore limité Catapult.", "data-admin-value=\"limits-empty\"");
        // the (empty) list is rendered hidden, so that a patch shows the first limit learned
        String list = none.substring(none.indexOf("data-admin-list=\"limits\""));
        assertThat(list.substring(0, list.indexOf('>'))).contains("hidden");

        when(apiService.adminRateLimits()).thenReturn(null);
        assertThat(render(Locale.FRENCH)).doesNotContain("admin-rl-row\"");
    }
}
