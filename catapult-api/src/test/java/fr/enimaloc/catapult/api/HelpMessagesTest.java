package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.config.I18nConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/** The help texts ApiHelpController serves: every card's title and body, in both languages, read as it serves them. */
class HelpMessagesTest {

    static Properties bundle(String name) throws IOException {
        Properties p = new Properties();
        try (InputStream in = HelpMessagesTest.class.getResourceAsStream("/lang/" + name)) {
            assertThat(in).as(name).isNotNull();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    static Set<String> helpKeys(Properties p) {
        Set<String> keys = new TreeSet<>();
        p.stringPropertyNames().stream().filter(k -> k.startsWith("help.")).forEach(keys::add);
        return keys;
    }

    @Test
    void everyCardHasATitleAndABody_inBothLanguages() throws IOException {
        Properties base = bundle("messages.properties");
        Properties fr = bundle("messages_fr.properties");
        assertThat(helpKeys(fr)).isEqualTo(helpKeys(base));
        for (String key : helpKeys(base)) {
            if (!key.endsWith(".title")) continue;
            String card = key.substring(0, key.length() - ".title".length());
            assertThat(base).as(card).containsKey(card + ".body");
        }
    }

    /**
     * As ApiHelpController asks for them: a title as written (no arguments, so no formatting: a doubled
     * apostrophe would show), a body formatted with ICU4J and the controller's arguments.
     */
    @Test
    void everyHelpText_readsAsTheControllerServesIt() throws IOException {
        MessageSource messages = new I18nConfig().messageSource();
        Object[] args = {7, "Just Chatting", "Catapult"};
        for (var bundle : java.util.Map.of("messages.properties", Locale.ROOT, "messages_fr.properties", Locale.FRENCH).entrySet()) {
            for (String key : helpKeys(bundle(bundle.getKey()))) {
                String as = bundle.getKey() + " " + key;
                if (key.endsWith(".title")) {
                    assertThat(messages.getMessage(key, null, "", bundle.getValue())).as(as).doesNotContain("''", "{");
                } else {
                    // a quoted placeholder would come out as written
                    assertThat(messages.getMessage(key, args, "", bundle.getValue())).as(as).doesNotContain("{0}", "{1}", "{2}");
                }
            }
        }
    }
}
