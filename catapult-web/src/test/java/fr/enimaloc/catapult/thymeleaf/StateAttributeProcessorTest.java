package fr.enimaloc.catapult.thymeleaf;

import fr.enimaloc.catapult.dto.integration.SpaState;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateAttributeProcessorTest {

    private String render(String template, Object state) {
        SpringTemplateEngine engine = new SpringTemplateEngine();
        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.HTML);
        engine.setTemplateResolver(resolver);
        engine.addDialect(new SPADialect());

        Context context = new Context();
        context.setVariable("state", state);

        return engine.process(template, context);
    }

    @Test
    void visibleFlags_renderDataAttributesWithoutHidden() {
        String html = render("<p spa:state=\"${state}\">x</p>",
                SpaState.flag("connected", true, "Steam.connected")
                        .and("rateLimited", true, "Steam.rateLimited", "!Other"));

        assertThat(html).contains("data-if=\"connected,rateLimited\"")
                .contains("data-on=\"connected:Steam.connected,rateLimited:Steam.rateLimited,rateLimited:!Other\"")
                .doesNotContain("hidden")
                .doesNotContain("data-value")
                .doesNotContain("spa:state");
    }

    @Test
    void oneFalseFlag_appendsHiddenAfterThClassappend() {
        String html = render("<p class=\"a\" th:classappend=\"'b'\" spa:state=\"${state}\">x</p>",
                SpaState.flag("connected", false, "Steam.connected"));

        assertThat(html).contains("class=\"a b hidden\"");
    }

    @Test
    void syncOnly_rendersDataValueAndNoDataIf() {
        String html = render("<p spa:state=\"${state}\">x</p>",
                SpaState.sync("textContent", "Mc.name", "Sync.name"));

        assertThat(html).contains("data-value=\"textContent:Mc.name,textContent:Sync.name\"")
                .doesNotContain("data-if")
                .doesNotContain("hidden");
    }

    @Test
    void nullState_leavesElementUntouched() {
        String html = render("<p class=\"a\" spa:state=\"${state}\">x</p>", null);

        assertThat(html).isEqualTo("<p class=\"a\">x</p>");
    }

    @Test
    void nonSpaStateValue_throwsDescriptiveException() {
        assertThatThrownBy(() -> render("<p spa:state=\"${state}\">x</p>", "nope"))
                .rootCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SpaState");
    }

    @Test
    void commaInToken_isRejectedAtConstruction() {
        assertThatThrownBy(() -> SpaState.flag("a,b", true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
