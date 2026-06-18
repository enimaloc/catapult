package fr.enimaloc.catapult.service.notification;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;

@Component
public class NotificationRenderer {

    private final Parser parser = Parser.builder().build();
    private final HtmlRenderer html = HtmlRenderer.builder().build();
    private final Safelist safelist = Safelist.basic();

    public String render(String markdown) {
        String rawHtml = html.render(parser.parse(markdown == null ? "" : markdown));
        return Jsoup.clean(rawHtml, safelist);
    }

    public static boolean isValidCtaUrl(String url) {
        if (url == null) return false;
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
