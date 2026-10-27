package fr.enimaloc.catapult.controller;


import fr.enimaloc.catapult.common.dto.admin.SocialLinkDto;
import fr.enimaloc.catapult.common.dto.admin.SocialPlatform;
import fr.enimaloc.catapult.config.I18nConfiguration;
import fr.enimaloc.catapult.config.TwitchatCatalogConfig;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** The footer written by index.html from the social links catapult-api answered. */
@WebMvcTest(controllers = {IndexController.class, SpaFragmentController.class})
@Import({ModelFiller.class, SiteMetaTags.class, SiteSocialLinks.class, LocalInstanceBusConfig.class,
        WebSecurityConfig.class, TwitchatCatalogConfig.class, I18nConfiguration.class})
class FooterSocialLinksTest {

    @Autowired MockMvc mvc;
    @Autowired SiteSocialLinks siteSocialLinks;
    @MockitoBean ApiService apiService;
    @MockitoBean NavEventStreamController navEvents;

    @BeforeEach
    void freshList() {
        siteSocialLinks.clear();
    }

    @AfterEach
    void forgetTheList() {
        siteSocialLinks.clear();
    }

    private String footer(SocialLinkDto... links) throws Exception {
        when(apiService.socialLinks()).thenReturn(List.of(links));
        String html = mvc.perform(get("/privacy")).andReturn().getResponse().getContentAsString();
        int start = html.indexOf("<footer");
        return html.substring(start, html.indexOf("</footer>", start));
    }

    @Test
    void footerShowsVisibleLinksInOrder() throws Exception {
        String footer = footer(
                new SocialLinkDto(1, SocialPlatform.DISCORD, null, "https://discord.gg/abc", true, 0),
                new SocialLinkDto(2, SocialPlatform.X, "Our X", "https://x.com/catapult", true, 1));

        assertThat(footer).contains("href=\"https://discord.gg/abc\"", "href=\"https://x.com/catapult\"",
                "title=\"Discord\"", "title=\"Our X\"", ">Discord</span>", ">Our X</span>");
        assertThat(footer.indexOf("discord.gg")).isLessThan(footer.indexOf("x.com"));
        assertThat(footer).doesNotContain("github.com/enimaloc");
    }

    @Test
    void footerRendersOnlyHttpsHrefs() throws Exception {
        String footer = footer(
                new SocialLinkDto(1, SocialPlatform.OTHER, "Evil", "javascript:alert(1)", true, 0),
                new SocialLinkDto(2, SocialPlatform.OTHER, "Plain", "http://plain.example", true, 1),
                new SocialLinkDto(3, SocialPlatform.GITHUB, null, "https://github.com/x", true, 2));

        assertThat(footer).doesNotContain("javascript:", "plain.example", "Evil", "Plain")
                .contains("href=\"https://github.com/x\"");
    }

    @Test
    void footerHasTheApiDocsLink() throws Exception {
        assertThat(footer()).contains("href=\"/docs/api\"", ">API</a>");
    }

    @Test
    void linksOpenInANewTabWithoutOpener() throws Exception {
        String footer = footer(new SocialLinkDto(1, SocialPlatform.MASTODON, null, "https://m.example/@c", true, 0));

        assertThat(footer).containsPattern(
                "<a[^>]*href=\"https://m\\.example/@c\"[^>]*target=\"_blank\"[^>]*rel=\"noopener noreferrer\"");
    }

    @Test
    void everyVisibleLinkHasAnIconAndAnAccessibleName() throws Exception {
        SocialLinkDto[] links = java.util.Arrays.stream(SocialPlatform.values())
                .map(p -> new SocialLinkDto(p.ordinal() + 1, p, null, "https://p" + p.ordinal() + ".example", true,
                        p.ordinal()))
                .toArray(SocialLinkDto[]::new);
        String footer = footer(links);

        for (SocialPlatform p : SocialPlatform.values()) {
            int at = footer.indexOf("data-platform=\"" + p + "\"");
            String anchor = footer.substring(footer.lastIndexOf("<a ", at));
            anchor = anchor.substring(0, anchor.indexOf("</a>"));
            assertThat(anchor).contains("aria-hidden=\"true\"").contains("title=\"");
            assertThat(anchor).containsPattern("footer-social-name\">[^<]+</span>");
            if (p == SocialPlatform.OTHER) {
                assertThat(anchor).contains("<mdui-icon").contains("name=\"link\"").doesNotContain("<svg");
            } else {
                assertThat(anchor).contains("<svg").contains("<path").doesNotContain("<mdui-icon");
            }
        }
    }

    @Test
    void anUppercaseSchemeIsRendered_aJavascriptOneStillIsnt() throws Exception {
        String footer = footer(
                new SocialLinkDto(1, SocialPlatform.OTHER, "Up", "HTTPS://up.example", true, 0),
                new SocialLinkDto(2, SocialPlatform.OTHER, "Js", "JavaScript:alert(1)", true, 1));

        assertThat(footer).contains("href=\"HTTPS://up.example\"").doesNotContain("alert(1)");
    }
}
