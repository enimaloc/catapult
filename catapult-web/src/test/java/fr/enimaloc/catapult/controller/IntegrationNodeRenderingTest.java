package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.dto.integration.Attrs;
import fr.enimaloc.catapult.dto.integration.IntegrationNode;
import fr.enimaloc.catapult.dto.integration.Message;
import fr.enimaloc.catapult.thymeleaf.SPADialect;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The integration node fragment, rendered alone for each new node type. */
class IntegrationNodeRenderingTest {

    private String render(IntegrationNode node) {
        SpringTemplateEngine engine = new SpringTemplateEngine();
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        engine.setTemplateResolver(resolver);
        engine.addDialect(new SPADialect());
        StaticMessageSource messages = new StaticMessageSource();
        messages.setUseCodeAsDefaultMessage(true);
        engine.setTemplateEngineMessageSource(messages);
        Context context = new Context(Locale.ROOT);
        context.setVariable("node", node);
        return engine.process("pages/channel-tab/integrations", Set.of("node-test"), context);
    }

    @Test
    void textField_carriesItsDataField() {
        String html = render(new IntegrationNode.TextField(Attrs.field("user.name"), Message.literal("Author"), "text", "Bot", false));
        assertThat(html).contains("<mdui-text-field").contains("data-field=\"user.name\"")
                .contains("label=\"Author\"").contains("value=\"Bot\"").doesNotContain("autosize");
    }

    @Test
    void multilineTextField_autosizes() {
        assertThat(render(new IntegrationNode.TextField(Attrs.NONE, Message.literal("M"), "text", null, true))).contains("autosize");
    }

    @Test
    void select_withSource_exposesIt() {
        String html = render(new IntegrationNode.Select(Attrs.field("id"), Message.literal("Trigger"), "t-1",
                List.of(new IntegrationNode.Select.Option("t-1", Message.literal("t-1"))), "{\"stable\":\"TRIGGERS_GET_ALL\"}"));
        assertThat(html).contains("data-source=\"{&quot;stable&quot;:&quot;TRIGGERS_GET_ALL&quot;}\"").contains("data-field=\"id\"");
    }

    @Test
    void repeater_rendersRowsAndATemplateRow() {
        IntegrationNode label = new IntegrationNode.TextField(Attrs.field("label"), Message.literal("Label"), "text", "Revert", false);
        IntegrationNode empty = new IntegrationNode.TextField(Attrs.field("label"), Message.literal("Label"), "text", null, false);
        String html = render(new IntegrationNode.Repeater(Attrs.field("actions"), Message.literal("Buttons"), List.of(List.of(label)), List.of(empty)));
        assertThat(html).contains("data-repeater").contains("data-field=\"actions\"")
                .contains("class=\"repeater-row\"").contains("<template>").contains("repeater-add");
    }

    @Test
    void collapse_rendersItsItems() {
        String html = render(new IntegrationNode.Collapse(Attrs.NONE, List.of(new IntegrationNode.Collapse.Item(
                Attrs.cls("twitchat-event"), "STREAM_STARTED", Message.literal("Stream"), List.of(new IntegrationNode.Hidden(Attrs.cls("x"), "v"))))));
        assertThat(html).contains("<mdui-collapse").contains("value=\"STREAM_STARTED\"").contains("Stream")
                .contains("type=\"hidden\"").contains("value=\"v\"");
    }
}
