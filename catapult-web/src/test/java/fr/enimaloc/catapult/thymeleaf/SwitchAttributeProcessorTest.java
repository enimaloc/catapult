package fr.enimaloc.catapult.thymeleaf;

import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import static org.assertj.core.api.Assertions.assertThat;

class SwitchAttributeProcessorTest {

    private String render(String template) {
        SpringTemplateEngine engine = new SpringTemplateEngine();
        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.HTML);
        engine.setTemplateResolver(resolver);
        engine.addDialect(new SPADialect());

        return engine.process(template, new Context());
    }

    @Test
    void valuePassesThroughVerbatimAsDataSwitch() {
        String html = render("<div spa:switch=\"caseNone:MinecraftEnrollEvent.status=NONE\">x</div>");

        assertThat(html).contains("data-switch=\"caseNone:MinecraftEnrollEvent.status=NONE\"");
    }

    @Test
    void originalAttributeIsRemovedFromOutput() {
        String html = render("<div spa:switch=\"caseNone:MinecraftEnrollEvent.status=NONE\">x</div>");

        assertThat(html).doesNotContain("spa:switch");
    }

    @Test
    void malformedEntryMissingColon_throwsDescriptiveExceptionNotAnIndexException() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> render("<div spa:switch=\"noColonHere\">x</div>"))
                .rootCause()
                .isNotInstanceOf(StringIndexOutOfBoundsException.class)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("noColonHere");
    }
}
