package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.chat.command.ast.CommandAst;
import fr.enimaloc.catapult.chat.command.dsl.CommandDslParser;
import fr.enimaloc.catapult.chat.command.js.JsCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Every {@code chat.preset.<key>.template} shipped in the real resource bundles must actually
 * parse and compile — a stray typo in a template edit (missing {@code .field}, unbalanced
 * {@code {if}}, etc.) would otherwise only surface the first time a streamer instantiates that
 * specific preset in that specific language.
 */
class ChatCommandPresetTemplatesParseTest {

    private static final CommandDslParser PARSER = new CommandDslParser();
    private static final JsCompiler COMPILER = new JsCompiler();

    static List<String> presetTemplateKeys() {
        List<String> keys = new ArrayList<>();
        for (String key : ResourceBundle.getBundle("lang.messages", Locale.ROOT).keySet()) {
            if (key.startsWith("chat.preset.") && key.endsWith(".template")) {
                keys.add(key);
            }
        }
        return keys;
    }

    @ParameterizedTest
    @MethodSource("presetTemplateKeys")
    void frenchTemplateParsesAndCompiles(String key) {
        assertParsesAndCompiles(key, Locale.FRANCE);
    }

    @ParameterizedTest
    @MethodSource("presetTemplateKeys")
    void englishTemplateParsesAndCompiles(String key) {
        assertParsesAndCompiles(key, Locale.ROOT);
    }

    @Test
    void thereAreSomePresetTemplatesToCheck() {
        assertThatCode(() -> {
            if (presetTemplateKeys().isEmpty()) {
                throw new AssertionError("No chat.preset.*.template keys found — bundle lookup is broken");
            }
        }).doesNotThrowAnyException();
    }

    private void assertParsesAndCompiles(String key, Locale locale) {
        String template = ResourceBundle.getBundle("lang.messages", locale).getString(key);
        assertThatCode(() -> {
            CommandAst ast = PARSER.parse(template);
            COMPILER.compile(ast);
        }).doesNotThrowAnyException();
    }
}
