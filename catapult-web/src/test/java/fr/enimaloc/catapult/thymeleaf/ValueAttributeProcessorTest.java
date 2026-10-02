package fr.enimaloc.catapult.thymeleaf;

import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import static org.assertj.core.api.Assertions.assertThat;

class ValueAttributeProcessorTest {

    private String render(String template) {
        SpringTemplateEngine engine = new SpringTemplateEngine();
        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.HTML);
        engine.setTemplateResolver(resolver);
        engine.addDialect(new SPADialect());

        return engine.process(template, new Context());
    }

    @Test
    void valuePassesThroughVerbatimAsDataValue() {
        String html = render("<input spa:value=\"checked:BotStateChangedEvent.state\">");

        assertThat(html).contains("data-value=\"checked:BotStateChangedEvent.state\"");
    }

    @Test
    void multipleEntriesPassThroughVerbatim() {
        String html = render("<span spa:value=\"textContent:GameChangedEvent.sourceName\">x</span>");

        assertThat(html).contains("data-value=\"textContent:GameChangedEvent.sourceName\"");
    }

    @Test
    void originalAttributeIsRemovedFromOutput() {
        String html = render("<input spa:value=\"checked:SomeEvent.field\">");

        assertThat(html).doesNotContain("spa:value");
    }

    @Test
    void malformedEntryMissingColon_throwsDescriptiveExceptionNotAnIndexException() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> render("<input spa:value=\"noColonHere\">"))
                .rootCause()
                .isNotInstanceOf(StringIndexOutOfBoundsException.class)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("noColonHere");
    }
}
