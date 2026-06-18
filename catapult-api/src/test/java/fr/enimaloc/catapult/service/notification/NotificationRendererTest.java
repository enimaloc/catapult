package fr.enimaloc.catapult.service.notification;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationRendererTest {

    private final NotificationRenderer renderer = new NotificationRenderer();

    @Test
    void render_basicMarkdown() {
        String html = renderer.render("**bold** and *italic*");
        assertThat(html).contains("<strong>bold</strong>").contains("<em>italic</em>");
    }

    @Test
    void render_stripsScriptTags() {
        String html = renderer.render("Hello <script>alert('xss')</script>");
        assertThat(html).doesNotContain("<script>");
    }

    @Test
    void render_stripsOnEventAttributes() {
        String html = renderer.render("<a href=\"#\" onclick=\"x()\">link</a>");
        assertThat(html).doesNotContain("onclick");
    }

    @Test
    void render_rejectsJavascriptHref() {
        String html = renderer.render("[bad](javascript:alert(1))");
        assertThat(html).doesNotContain("javascript:");
    }

    @Test
    void validateCtaUrl_acceptsHttp() {
        assertThat(NotificationRenderer.isValidCtaUrl("https://example.com/x")).isTrue();
        assertThat(NotificationRenderer.isValidCtaUrl("http://example.com/x")).isTrue();
    }

    @Test
    void validateCtaUrl_rejectsJavascript() {
        assertThat(NotificationRenderer.isValidCtaUrl("javascript:alert(1)")).isFalse();
        assertThat(NotificationRenderer.isValidCtaUrl("data:text/html,xx")).isFalse();
        assertThat(NotificationRenderer.isValidCtaUrl("ftp://x")).isFalse();
    }
}
