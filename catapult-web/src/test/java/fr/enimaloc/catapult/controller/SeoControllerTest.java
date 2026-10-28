package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.security.WebSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SeoController.class)
@Import(WebSecurityConfig.class)
class SeoControllerTest {

    @Autowired MockMvc mvc;

    @Test
    void robots_keepsCrawlersOffTheFragmentsAndPointsAtTheSitemap() throws Exception {
        String robots = mvc.perform(get("/robots.txt"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andReturn().getResponse().getContentAsString();

        assertThat(robots)
                .contains("User-agent: *")
                .contains("Disallow: /spa/")
                .contains("Disallow: /widget/")
                .doesNotContain("Disallow: /channel")
                .contains("Sitemap: http://localhost/sitemap.xml");
    }

    @Test
    void sitemap_listsOnlyThePublicPages() throws Exception {
        String sitemap = mvc.perform(get("/sitemap.xml"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andReturn().getResponse().getContentAsString();

        assertThat(sitemap)
                .startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .contains("<loc>http://localhost/</loc>")
                .contains("<loc>http://localhost/privacy</loc>")
                .doesNotContain("channel");
    }
}
