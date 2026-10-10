package fr.enimaloc.catapult.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.NoSuchMessageException;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecursiveMessageSourceTest {

    private RecursiveMessageSource messages;

    @BeforeEach
    void setUp() {
        messages = new RecursiveMessageSource();
        messages.setBasenames("test-lang/recursive");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
    }

    private String get(String code, Object... args) {
        return messages.getMessage(code, args.length == 0 ? null : args, Locale.ENGLISH);
    }

    @Test
    void expandsReferencesToOtherKeys() {
        assertThat(get("greeting")).isEqualTo("Welcome to Catapult");
    }

    @Test
    void expandsReferencesRecursively() {
        assertThat(get("nested")).isEqualTo("Welcome to Catapult!");
    }

    @Test
    void expandsEveryReferenceInAMessage() {
        assertThat(get("two")).isEqualTo("Catapult & Catapult");
    }

    @Test
    void leavesUnknownReferencesAsTheyAre() {
        assertThat(get("unknown")).isEqualTo("Hello #{missing}");
    }

    @Test
    void keepsMessageArgumentsWorking() {
        assertThat(get("with.args", "Alice")).isEqualTo("Alice uses Catapult");
    }

    @Test
    void resolvesReferencesInTheRequestedLocale() {
        assertThat(messages.getMessage("greeting", null, Locale.FRENCH)).isEqualTo("Bienvenue sur Catapult");
    }

    @Test
    void returnsMessagesWithoutReferencesUnchanged() {
        assertThat(get("plain")).isEqualTo("No references");
        assertThat(get("with.args", 5)).isEqualTo("5 uses Catapult");
    }

    @Test
    void stopsCyclicReferences() {
        assertThatThrownBy(() -> get("loop.a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Maximum i18n reference depth");
    }

    @Test
    void unknownKeysStillFail() {
        assertThatThrownBy(() -> get("does.not.exist")).isInstanceOf(NoSuchMessageException.class);
        assertThatThrownBy(() -> get("does.not.exist", "arg")).isInstanceOf(NoSuchMessageException.class);
    }
}
