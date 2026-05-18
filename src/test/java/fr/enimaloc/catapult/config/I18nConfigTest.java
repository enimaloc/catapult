package fr.enimaloc.catapult.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import java.util.Locale;
import static org.assertj.core.api.Assertions.assertThat;

class I18nConfigTest {

    private MessageSource messageSource;

    @BeforeEach
    void setUp() {
        messageSource = new I18nConfig().messageSource();
    }

    @Test
    void missingKeyReturnsCodeItself() {
        assertThat(messageSource.getMessage("nonexistent.key.xyz", null, Locale.ENGLISH))
            .isEqualTo("nonexistent.key.xyz");
    }

    @Test
    void resolvesPluralZeroEN() {
        assertThat(messageSource.getMessage("obs.predicate.count", new Object[]{0}, Locale.ENGLISH))
            .isEqualTo("No predicate");
    }

    @Test
    void resolvesPluralOneEN() {
        assertThat(messageSource.getMessage("obs.predicate.count", new Object[]{1}, Locale.ENGLISH))
            .isEqualTo("1 predicate");
    }

    @Test
    void resolvesPluralManyEN() {
        assertThat(messageSource.getMessage("obs.predicate.count", new Object[]{3}, Locale.ENGLISH))
            .isEqualTo("3 predicates");
    }

    @Test
    void resolvesPluralZeroFR() {
        assertThat(messageSource.getMessage("obs.predicate.count", new Object[]{0}, Locale.FRENCH))
            .isEqualTo("Aucun prédicat");
    }

    @Test
    void resolvesPluralOneFR() {
        assertThat(messageSource.getMessage("obs.predicate.count", new Object[]{1}, Locale.FRENCH))
            .isEqualTo("1 prédicat");
    }
}
