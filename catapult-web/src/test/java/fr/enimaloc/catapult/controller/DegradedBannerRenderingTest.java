package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.config.TwitchatCatalogConfig;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.degraded.DegradedState;
import fr.enimaloc.catapult.service.degraded.DegradedView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The degraded banner as the server renders it in the shell (fragments/degraded.html): shown with the
 * intervals catapult-api applies, the link labelled after what it points to, and the labels degraded.js
 * words the live updates with.
 */
@WebMvcTest(controllers = IndexController.class)
@Import({ModelFiller.class, WebSecurityConfig.class, TwitchatCatalogConfig.class})
class DegradedBannerRenderingTest {
    private static final String INCIDENT = "https://status.example.test/incident/abc";
    private static final String STATUS_PAGE = "https://status.example.test";

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean NavEventStreamController navEvents;
    @MockitoBean DegradedState degradedState;

    private String render(DegradedView view) throws Exception {
        when(degradedState.current()).thenReturn(view);
        return mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** The opening tag of the first element carrying {@code marker}. */
    private static String tag(String html, String marker) {
        Matcher matcher = Pattern.compile("<[a-z-]+\\s[^>]*" + Pattern.quote(marker) + "[^>]*>").matcher(html);
        assertThat(matcher.find()).as("an element with %s", marker).isTrue();
        return matcher.group();
    }

    private static boolean hidden(String tag) {
        return Pattern.compile("\\shidden(=\"hidden\")?[\\s>/]").matcher(tag).find();
    }

    @Test
    void notDegraded_bannerIsHidden() throws Exception {
        String html = render(DegradedView.NONE);

        assertThat(hidden(tag(html, "id=\"degraded-banner\""))).isTrue();
    }

    @Test
    void degraded_showsTheAppliedValues() throws Exception {
        String html = render(new DegradedView(true, 60, 15, STATUS_PAGE, false));

        assertThat(hidden(tag(html, "id=\"degraded-banner\""))).isFalse();
        assertThat(hidden(tag(html, "data-degraded-line=\"game\""))).isFalse();
        assertThat(hidden(tag(html, "data-degraded-line=\"minecraft\""))).isFalse();
        assertThat(html).contains("Category changes can take up to 60 s.",
                "Minecraft friends are synced every 15 min.", "Everything else works as usual.");
    }

    @Test
    void degraded_withoutMinecraft_hidesItsLine() throws Exception {
        String html = render(new DegradedView(true, 60, null, STATUS_PAGE, false));

        assertThat(hidden(tag(html, "data-degraded-line=\"minecraft\""))).isTrue();
        assertThat(hidden(tag(html, "data-degraded-line=\"game\""))).isFalse();
    }

    @Test
    void incidentLink_isLabelledAsIncident() throws Exception {
        String html = render(new DegradedView(true, 60, 15, INCIDENT, true));

        String link = tag(html, "data-degraded-link");
        assertThat(hidden(link)).isFalse();
        assertThat(link).contains("href=\"" + INCIDENT + "\"", "target=\"_blank\"").containsPattern("rel=\"[^\"]*noopener");
        assertThat(html).contains(">View the incident</a>");
    }

    /** The link shows while the banner is folded: after the details, not in them, and before the ✕. */
    @Test
    void link_isOutOfTheFoldedDetails() throws Exception {
        String html = render(new DegradedView(true, 60, 15, INCIDENT, true));

        int detailsEnd = html.indexOf("</details>", html.indexOf("id=\"degraded-banner\""));
        assertThat(detailsEnd).isPositive();
        assertThat(html.indexOf("data-degraded-link")).isGreaterThan(detailsEnd)
                .isLessThan(html.indexOf("data-degraded-dismiss"));
    }

    @Test
    void statusPageLink_isLabelledAsStatusPage() throws Exception {
        String html = render(new DegradedView(true, 60, 15, STATUS_PAGE, false));

        assertThat(tag(html, "data-degraded-link")).contains("href=\"" + STATUS_PAGE + "\"");
        assertThat(html).contains(">Status page</a>");
    }

    @Test
    void noLink_hidesTheAnchor() throws Exception {
        String html = render(new DegradedView(true, 60, 15, null, false));

        String link = tag(html, "data-degraded-link");
        assertThat(hidden(link)).isTrue();
        assertThat(link).doesNotContain("href=");
    }

    @Test
    void labelsForTheScript_areThere() throws Exception {
        String banner = tag(render(DegradedView.NONE), "id=\"degraded-banner\"");

        assertThat(banner).contains(
                "data-label-game=\"Category changes can take up to {0} s.\"",
                "data-label-minecraft=\"Minecraft friends are synced every {0} min.\"",
                "data-label-incident=\"View the incident\"",
                "data-label-status=\"Status page\"");
    }
}
