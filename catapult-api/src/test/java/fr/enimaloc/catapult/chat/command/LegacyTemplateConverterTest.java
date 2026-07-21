package fr.enimaloc.catapult.chat.command;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyTemplateConverterTest {

    private final LegacyTemplateConverter converter = new LegacyTemplateConverter();

    @Test
    void wrapsExistingTemplateSyntaxWithoutBehaviorChange() {
        String template = "Now playing {game#name|no game}!";
        String astJson = converter.toAstJson(template);

        assertThat(astJson).contains("\"type\":\"placeholder\"");
        assertThat(astJson).contains("\"path\":\"game#name\"");
    }
}
