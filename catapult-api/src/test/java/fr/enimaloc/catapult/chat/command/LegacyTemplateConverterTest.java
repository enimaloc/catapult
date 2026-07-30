package fr.enimaloc.catapult.chat.command;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyTemplateConverterTest {

    private final LegacyTemplateConverter converter = new LegacyTemplateConverter();

    @Test
    void wrapsExistingTemplateSyntaxAsAnImplicitPrintOfAContextGet() {
        // The inline "|no game" fallback is intentionally dropped, preserving the exact
        // pre-existing behavior — see DynamicChatCommand#resolvePlaceholder, which consults
        // the DB-configured ChatCommandFallback instead. Not a regression of this rewrite.
        String template = "Now playing {game#name|no game}!";
        String astJson = converter.toAstJson(template);

        assertThat(astJson).contains("\"statements\"");
        assertThat(astJson).contains("\"type\":\"print\"");
        assertThat(astJson).contains("\"type\":\"context-get\"");
        assertThat(astJson).contains("\"path\":\"game#name\"");
        assertThat(astJson).doesNotContain("no game");
    }

    @Test
    void wrapsPlainLiteralTextAsAnImplicitPrint() {
        String astJson = converter.toAstJson("hello world");
        assertThat(astJson).contains("\"type\":\"print\"");
        assertThat(astJson).contains("\"type\":\"literal\"");
        assertThat(astJson).contains("\"value\":\"hello world\"");
    }
}
