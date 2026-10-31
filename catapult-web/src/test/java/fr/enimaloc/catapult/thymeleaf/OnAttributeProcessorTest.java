package fr.enimaloc.catapult.thymeleaf;

import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import static org.assertj.core.api.Assertions.assertThat;

class OnAttributeProcessorTest {

    private String render(String template) {
        SpringTemplateEngine engine = new SpringTemplateEngine();
        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.HTML);
        engine.setTemplateResolver(resolver);
        engine.addDialect(new SPADialect());

        return engine.process(template, new Context());
    }

    @Test
    void valuePassesThroughVerbatimAsDataOn() {
        String html = render("<p spa:on=\"live:ChannelLiveStateEvent.state\">x</p>");

        assertThat(html).contains("data-on=\"live:ChannelLiveStateEvent.state\"");
    }

    @Test
    void multipleEntriesPassThroughVerbatim() {
        String html = render("<p spa:on=\"connected:SteamConnectionStateEvent.connected,rateLimited:SteamConnectionStateEvent.rateLimited\">x</p>");

        assertThat(html).contains("data-on=\"connected:SteamConnectionStateEvent.connected,rateLimited:SteamConnectionStateEvent.rateLimited\"");
    }

    @Test
    void originalAttributeIsRemovedFromOutput() {
        String html = render("<p spa:on=\"flag:SomeEvent.field\">x</p>");

        assertThat(html).doesNotContain("spa:on");
    }

    @Test
    void malformedEntryMissingColon_throwsDescriptiveExceptionNotAnIndexException() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> render("<p spa:on=\"noColonHere\">x</p>"))
                .rootCause()
                .isNotInstanceOf(StringIndexOutOfBoundsException.class)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("noColonHere");
    }
}
