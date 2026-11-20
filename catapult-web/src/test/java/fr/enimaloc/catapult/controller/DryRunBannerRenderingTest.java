package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.config.TwitchatCatalogConfig;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.degraded.DegradedLabels;
import fr.enimaloc.catapult.service.dryrun.DryRunMembers;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The dry-run banner as the shell renders it for the member in dry run (fragments/dry-run.html). */
@WebMvcTest(controllers = IndexController.class)
@Import({ModelFiller.class, DegradedLabels.class, WebSecurityConfig.class, TwitchatCatalogConfig.class})
class DryRunBannerRenderingTest {

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean NavEventStreamController navEvents;
    @MockitoBean DryRunMembers dryRunMembers;

    private String render(boolean loggedIn, Locale locale) throws Exception {
        var request = get("/").locale(locale);
        if (loggedIn) request = request.sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt");
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void aMemberInDryRun_getsTheBanner_itsTurnOffButton_andItsConfirmation() throws Exception {
        when(dryRunMembers.isCurrentActive()).thenReturn(true);

        String html = render(true, Locale.ENGLISH);

        assertThat(html).contains("id=\"dry-run-banner\"",
                        "Catapult is in observation mode (dry run): it does not change your channel.",
                        "id=\"dry-run-disable\"", ">Turn off<", "id=\"dry-run-dialog\"",
                        "Dry run is usually used to debug a specific situation, at the team&#39;s request. If you turn it off,"
                                + " Catapult starts changing your channel again right away, and only an administrator can turn it back on.",
                        "/js/dry-run-");
    }

    @Test
    void theBanner_isInFrench_forAFrenchMember() throws Exception {
        when(dryRunMembers.isCurrentActive()).thenReturn(true);

        String html = render(true, Locale.FRENCH);

        assertThat(html).contains("Catapult est en mode observation (dry run) : il ne modifie pas votre chaîne.",
                ">Désactiver<", "Le mode dry run sert généralement à déboguer une situation précise");
    }

    @Test
    void aMemberOutOfDryRun_getsNoBanner() throws Exception {
        when(dryRunMembers.isCurrentActive()).thenReturn(false);

        assertThat(render(true, Locale.ENGLISH)).doesNotContain("dry-run-banner", "dry-run-dialog", "/js/dry-run-");
    }

    @Test
    void aVisitor_getsNoBanner_andNothingIsAsked() throws Exception {
        assertThat(render(false, Locale.ENGLISH)).doesNotContain("dry-run-banner");

        verify(dryRunMembers, never()).isCurrentActive();
    }
}
