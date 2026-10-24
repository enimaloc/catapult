package fr.enimaloc.catapult.service.notification;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
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

    /**
     * The body as plain text, for where no HTML shows (Twitchat, Twitch chat): markup dropped,
     * blocks joined by a space, each link as {@code text (url)} (just {@code url} when they match).
     */
    public String toPlainText(String markdown) {
        Document doc = Jsoup.parse(render(markdown));
        for (Element link : doc.select("a[href]")) {
            String href = link.attr("href");
            String text = link.text();
            link.replaceWith(new TextNode(text.isBlank() || text.equals(href) ? href : text + " (" + href + ")"));
        }
        return doc.text();
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
