package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.dto.index.SpaPage;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.stream.Collectors;

/** What crawlers read before the pages: where not to go, and the public pages to list. */
@Controller
public class SeoController {

    /**
     * Keeps crawlers off the SPA fragments (a duplicate of every page), the token-bearing widget
     * and the endpoints no visitor lands on. A member's page is not blocked here but marked
     * noindex (see index.html): a blocked page is never fetched, so its noindex would go unseen.
     */
    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    String robots() {
        return """
                User-agent: *
                Disallow: /spa/
                Disallow: /widget/
                Disallow: /events/
                Disallow: /me/
                Disallow: /auth/
                Disallow: /oauth2/
                Disallow: /mock/

                Sitemap: %s
                """.formatted(ModelFiller.absoluteUrl("/sitemap.xml"));
    }

    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @ResponseBody
    String sitemap() {
        String urls = SiteCatalog.PAGES.stream()
                .filter(page -> page.isIndexed() && !page.isDynamic())
                .map(SpaPage::getId)
                .map(id -> "  <url><loc>%s</loc></url>".formatted(ModelFiller.absoluteUrl("/" + id)))
                .collect(Collectors.joining("\n"));
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                %s
                </urlset>
                """.formatted(urls);
    }
}
