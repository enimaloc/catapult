package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.config.I18nConfiguration;
import fr.enimaloc.catapult.config.TwitchatCatalogConfig;
import fr.enimaloc.catapult.dto.changelog.ChangelogSection;
import fr.enimaloc.catapult.dto.changelog.ChangelogSection.Entry;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import fr.enimaloc.catapult.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(controllers = ChangelogController.class)
@Import({ModelFiller.class, SiteMetaTags.class, WebSecurityConfig.class, TwitchatCatalogConfig.class, I18nConfiguration.class})
class ChangelogControllerTest {
    static final ChangelogSection[] SECTIONS = {
            new ChangelogSection("0.3.7", List.of(
                    new Entry("a1", "fix", "web", "<b>escaped</b> fix", false),
                    new Entry("a2", "feat", null, "a feature", true),
                    new Entry("a3", "chore", "release", "bump", false))),
            new ChangelogSection("0.3.6", List.of(new Entry("b1", "feat", "api", "older", false)))
    };

    @Autowired MockMvc mvc;
    @MockitoBean ApiClient apiClient;
    @MockitoBean ApiService apiService;
    @MockitoBean NavEventStreamController navEvents;

    @Test
    void servesTheVersions_fullAndAsAFragment_byKind() throws Exception {
        when(apiClient.getAnonymous("/api/changelog", ChangelogSection[].class)).thenReturn(new ApiClient.Result<>(200, SECTIONS));

        String full = mvc.perform(get("/changelog").locale(Locale.ENGLISH)).andExpect(status().isOk())
                .andExpect(view().name("index")).andReturn().getResponse().getContentAsString();
        String fragment = mvc.perform(get("/spa/changelog").locale(Locale.ENGLISH)).andExpect(status().isOk())
                .andExpect(view().name("pages/changelog :: changelog")).andReturn().getResponse().getContentAsString();

        for (String html : List.of(full, fragment)) {
            assertThat(html).contains("0.3.7", "0.3.6", "(web)", "a feature", "BREAKING")
                    .contains("&lt;b&gt;escaped&lt;/b&gt; fix").doesNotContain("<b>escaped</b>")
                    .doesNotContain("id=\"changelog-unavailable\"");
            // New features first, then fixes, then the rest.
            assertThat(html.indexOf("a feature")).isLessThan(html.indexOf("escaped")).isLessThan(html.indexOf("bump"));
        }
        assertThat(full).contains("<title>Changelog - ").contains("rel=\"canonical\"");
    }

    @Test
    void saysSo_whenCatapultApiCantGiveIt() throws Exception {
        when(apiClient.getAnonymous("/api/changelog", ChangelogSection[].class)).thenReturn(new ApiClient.Result<>(502, null));

        String html = mvc.perform(get("/spa/changelog")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("id=\"changelog-unavailable\"");
    }

    @Test
    void keepsTheVersionsAFewMinutes_butNotAFailure() {
        ApiClient client = mock(ApiClient.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-10-20T10:00:00Z"));
        ChangelogController controller = new ChangelogController(mock(ModelFiller.class), client, clock);
        when(client.getAnonymous(eq("/api/changelog"), any()))
                .thenReturn(new ApiClient.Result<>(502, null), new ApiClient.Result<>(200, SECTIONS));

        assertThat(controller.sections()).isNull();
        assertThat(controller.sections()).hasSize(2);
        assertThat(controller.sections()).hasSize(2);
        verify(client, times(2)).getAnonymous(eq("/api/changelog"), any());

        clock.advance(ChangelogController.TTL);
        controller.sections();
        verify(client, times(3)).getAnonymous(eq("/api/changelog"), any());
    }

    @Test
    void groupsLeaveOutTheKindsAVersionHasNoneOf() {
        assertThat(SECTIONS[1].groups()).extracting(ChangelogSection.Group::kind).containsExactly("feat");
        assertThat(new ChangelogSection("x", null).groups()).isEmpty();
    }
}
