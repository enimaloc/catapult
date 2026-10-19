package fr.enimaloc.catapult.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/** The help texts ApiHelpController serves: every card's title and body, in both languages, as valid MessageFormat. */
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

    @Test
    void everyHelpText_formatsWithTheControllersArguments_withoutAStrayQuote() throws IOException {
        Object[] args = {7, "Just Chatting", "Catapult"};
        for (String name : new String[]{"messages.properties", "messages_fr.properties"}) {
            Properties p = bundle(name);
            for (String key : helpKeys(p)) {
                String text = new MessageFormat(p.getProperty(key), Locale.ROOT).format(args);
                // an unescaped apostrophe swallows what follows it, placeholders included
                assertThat(text).as(name + " " + key).doesNotContain("{0}", "{1}", "{2}");
                assertThat(p.getProperty(key).replace("''", "")).as(name + " " + key).doesNotContain("'");
            }
        }
    }
}
