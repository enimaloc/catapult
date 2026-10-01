package fr.enimaloc.catapult.thymeleaf;

import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import static org.assertj.core.api.Assertions.assertThat;

class VisibleWhenAttributeProcessorTest {

    record TestChannel(boolean connected, boolean rateLimited) {}

    private String render(String template, Object ch) {
        // SpringTemplateEngine (SpEL-backed), not plain TemplateEngine (OGNL-backed): this
        // Spring Boot app only ever wires Thymeleaf through SpringTemplateEngine in
        // production, and OGNL isn't on the classpath here as a result — using plain
        // TemplateEngine in this test would require adding a dependency the app itself
        // never needs.
        SpringTemplateEngine engine = new SpringTemplateEngine();
        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.HTML);
        engine.setTemplateResolver(resolver);
        engine.addDialect(new VisibilityDialect());

        Context context = new Context();
        context.setVariable("ch", ch);

        return engine.process(template, context);
    }

    @Test
    void allFlagsTrue_rendersWithoutHiddenClass() {
        String html = render(
                "<p sp:visible-when=\"connected:${ch.connected()},rateLimited:${ch.rateLimited()}\">x</p>",
                new TestChannel(true, true));

        assertThat(html).contains("data-visible-when=\"connected,rateLimited\"");
        assertThat(html).doesNotContain("hidden");
    }

    @Test
    void oneFlagFalse_appendsHiddenClass() {
        String html = render(
                "<p sp:visible-when=\"connected:${ch.connected()},rateLimited:${ch.rateLimited()}\">x</p>",
                new TestChannel(true, false));

        assertThat(html).contains("class=\"hidden\"");
        assertThat(html).contains("data-visible-when=\"connected,rateLimited\"");
    }

    @Test
    void existingClassAttribute_keepsOriginalClassAlongsideHidden() {
        String html = render(
                "<p class=\"diag\" sp:visible-when=\"flag:${ch.rateLimited()}\">x</p>",
                new TestChannel(true, false));

        assertThat(html).contains("class=\"diag hidden\"");
    }

    @Test
    void visibleElement_doesNotGetHiddenClassAtAll() {
        String html = render(
                "<p class=\"diag\" sp:visible-when=\"flag:${ch.connected()}\">x</p>",
                new TestChannel(true, false));

        assertThat(html).contains("class=\"diag\"");
        assertThat(html).doesNotContain("hidden");
    }

    @Test
    void originalAttributeIsRemovedFromOutput() {
        String html = render(
                "<p sp:visible-when=\"flag:${ch.connected()}\">x</p>",
                new TestChannel(true, false));

        assertThat(html).doesNotContain("sp:visible-when");
    }

    @Test
    void throwingExpression_propagatesLikeThClassappendWould() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> render(
                        "<p sp:visible-when=\"flag:${ch.connected()}\">x</p>", null))
                .isInstanceOf(org.thymeleaf.exceptions.TemplateProcessingException.class);
    }
}
