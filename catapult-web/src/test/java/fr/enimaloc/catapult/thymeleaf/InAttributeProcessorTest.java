package fr.enimaloc.catapult.thymeleaf;

import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import static org.assertj.core.api.Assertions.assertThat;

class InAttributeProcessorTest {

    private String render(String template) {
        SpringTemplateEngine engine = new SpringTemplateEngine();
        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.HTML);
        engine.setTemplateResolver(resolver);
        engine.addDialect(new SPADialect());

        return engine.process(template, new Context());
    }

    @Test
    void valuePassesThroughVerbatimAsDataIn() {
        String html = render("<input spa:in=\"checked:TwUpdatedEvent.tws\">");

        assertThat(html).contains("data-in=\"checked:TwUpdatedEvent.tws\"");
    }

    @Test
    void originalAttributeIsRemovedFromOutput() {
        String html = render("<input spa:in=\"checked:TwUpdatedEvent.tws\">");

        assertThat(html).doesNotContain("spa:in");
    }

    @Test
    void malformedEntryMissingColon_throwsDescriptiveExceptionNotAnIndexException() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> render("<input spa:in=\"noColonHere\">"))
                .rootCause()
                .isNotInstanceOf(StringIndexOutOfBoundsException.class)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("noColonHere");
    }
}
