package fr.enimaloc.catapult.thymeleaf;

import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import static org.assertj.core.api.Assertions.assertThat;

class IfAttributeProcessorTest {

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
        engine.addDialect(new SPADialect());

        Context context = new Context();
        context.setVariable("ch", ch);

        return engine.process(template, context);
    }

    @Test
    void allFlagsTrue_rendersWithoutHiddenClass() {
        String html = render(
                "<p spa:if=\"connected:${ch.connected()},rateLimited:${ch.rateLimited()}\">x</p>",
                new TestChannel(true, true));

        assertThat(html).contains("data-if=\"connected,rateLimited\"");
        assertThat(html).doesNotContain("hidden");
    }

    @Test
    void oneFlagFalse_appendsHiddenClass() {
        String html = render(
                "<p spa:if=\"connected:${ch.connected()},rateLimited:${ch.rateLimited()}\">x</p>",
                new TestChannel(true, false));

        assertThat(html).contains("class=\"hidden\"");
        assertThat(html).contains("data-if=\"connected,rateLimited\"");
    }

    @Test
    void existingClassAttribute_keepsOriginalClassAlongsideHidden() {
        String html = render(
                "<p class=\"diag\" spa:if=\"flag:${ch.rateLimited()}\">x</p>",
                new TestChannel(true, false));

        assertThat(html).contains("class=\"diag hidden\"");
    }

    @Test
    void visibleElement_doesNotGetHiddenClassAtAll() {
        String html = render(
                "<p class=\"diag\" spa:if=\"flag:${ch.connected()}\">x</p>",
                new TestChannel(true, false));

        assertThat(html).contains("class=\"diag\"");
        assertThat(html).doesNotContain("hidden");
    }

    @Test
    void originalAttributeIsRemovedFromOutput() {
        String html = render(
                "<p spa:if=\"flag:${ch.connected()}\">x</p>",
                new TestChannel(true, false));

        assertThat(html).doesNotContain("spa:if");
    }

    @Test
    void throwingExpression_propagatesLikeThClassappendWould() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> render(
                        "<p spa:if=\"flag:${ch.connected()}\">x</p>", null))
                .isInstanceOf(org.thymeleaf.exceptions.TemplateProcessingException.class);
    }

    record TestChannelWithName(String displayName) {}

    @Test
    void nonBooleanTruthyExpression_isTreatedAsVisible() {
        // A non-empty String is truthy by Thymeleaf's own evaluation rules (same as
        // th:if="${ch.displayName()}" would treat it) — spa:if must agree,
        // not silently hide the element because the result isn't a literal Boolean.TRUE.
        String html = render(
                "<p spa:if=\"hasName:${ch.displayName()}\">x</p>",
                new TestChannelWithName("enimaloc"));

        assertThat(html).doesNotContain("hidden");
    }

    @Test
    void malformedAttributeValue_throwsDescriptiveExceptionNotAnIndexException() {
        // Thymeleaf always wraps processor exceptions with a generic message that echoes
        // the raw template text, so asserting on the outer exception alone would pass even
        // for a bare StringIndexOutOfBoundsException (sep == -1 from a missing ':'). The
        // real assertion is on the root cause: it must be a deliberate, descriptive error
        // naming the bad value, not an unchecked index exception with no context.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> render(
                        "<p spa:if=\"noColonHere\">x</p>",
                        new TestChannel(true, true)))
                .rootCause()
                .isNotInstanceOf(StringIndexOutOfBoundsException.class)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("noColonHere");
    }
}
