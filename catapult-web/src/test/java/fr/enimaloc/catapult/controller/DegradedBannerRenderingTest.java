package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.DegradedEffectDto;
import fr.enimaloc.catapult.config.TwitchatCatalogConfig;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.degraded.DegradedLabels;
import fr.enimaloc.catapult.service.degraded.DegradedState;
import fr.enimaloc.catapult.service.degraded.DegradedView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.HtmlUtils;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The degraded banner as the server renders it in the shell (fragments/degraded.html): shown or not, the
 * link labelled after what it points to, and what degraded.js words the effects' lines with (the labels and
 * the initial view).
 */
@WebMvcTest(controllers = IndexController.class)
@Import({ModelFiller.class, DegradedLabels.class, WebSecurityConfig.class, TwitchatCatalogConfig.class})
class DegradedBannerRenderingTest {
    private static final String INCIDENT = "https://status.example.test/incident/abc";
    private static final String STATUS_PAGE = "https://status.example.test";
    private static final List<DegradedEffectDto> EFFECTS = List.of(
            new DegradedEffectDto("app.polling.interval-seconds", "60", "30"));

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

    /** The value of {@code tag}'s attribute {@code name}, unescaped. */
    private static String attr(String tag, String name) {
        Matcher matcher = Pattern.compile("\\s" + Pattern.quote(name) + "=\"([^\"]*)\"").matcher(tag);
        assertThat(matcher.find()).as("%s in %s", name, tag).isTrue();
        return HtmlUtils.htmlUnescape(matcher.group(1));
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
    void degraded_showsTheBanner_theScriptWordsTheEffects() throws Exception {
        String html = render(new DegradedView(true, EFFECTS, STATUS_PAGE, false));

        assertThat(hidden(tag(html, "id=\"degraded-banner\""))).isFalse();
        assertThat(html).contains("Everything else works as usual.").doesNotContain("data-degraded-effect");
    }

    @Test
    void incidentLink_isLabelledAsIncident() throws Exception {
        String html = render(new DegradedView(true, EFFECTS, INCIDENT, true));

        String link = tag(html, "data-degraded-link");
        assertThat(hidden(link)).isFalse();
        assertThat(link).contains("href=\"" + INCIDENT + "\"", "target=\"_blank\"").containsPattern("rel=\"[^\"]*noopener");
        assertThat(html).contains(">View the incident</a>");
    }

    /** The link shows while the banner is folded: after the details, not in them, and before the ✕. */
    @Test
    void link_isOutOfTheFoldedDetails() throws Exception {
        String html = render(new DegradedView(true, EFFECTS, INCIDENT, true));

        int detailsEnd = html.indexOf("</details>", html.indexOf("id=\"degraded-banner\""));
        assertThat(detailsEnd).isPositive();
        assertThat(html.indexOf("data-degraded-link")).isGreaterThan(detailsEnd)
                .isLessThan(html.indexOf("data-degraded-dismiss"));
    }

    @Test
    void statusPageLink_isLabelledAsStatusPage() throws Exception {
        String html = render(new DegradedView(true, EFFECTS, STATUS_PAGE, false));

        assertThat(tag(html, "data-degraded-link")).contains("href=\"" + STATUS_PAGE + "\"");
        assertThat(html).contains(">Status page</a>");
    }

    @Test
    void noLink_hidesTheAnchor() throws Exception {
        String html = render(new DegradedView(true, EFFECTS, null, false));

        String link = tag(html, "data-degraded-link");
        assertThat(hidden(link)).isTrue();
        assertThat(link).doesNotContain("href=");
    }

    @Test
    void labelsForTheScript_areThere() throws Exception {
        String banner = tag(render(DegradedView.NONE), "id=\"degraded-banner\"");

        assertThat(banner).contains(
                "data-label-incident=\"View the incident\"",
                "data-label-status=\"Status page\"");
    }

    @Test
    void labels_areThereEvenWhenNotDegraded() throws Exception {
        String banner = tag(render(DegradedView.NONE), "id=\"degraded-banner\"");

        assertThat(attr(banner, "data-labels"))
                .contains("\"app.polling.interval-seconds\":\"Category changes can take up to {0}\"");
        assertThat(attr(banner, "data-label-normal")).isEqualTo("({0} normally)");
    }

    @Test
    void initialView_isEmbeddedForTheScript() throws Exception {
        String banner = tag(render(new DegradedView(true, EFFECTS, null, false)), "id=\"degraded-banner\"");

        assertThat(attr(banner, "data-view")).contains("\"degraded\":true", "\"normalValue\":\"30\"");
    }
}
