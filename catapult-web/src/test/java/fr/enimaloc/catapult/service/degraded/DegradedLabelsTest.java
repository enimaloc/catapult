package fr.enimaloc.catapult.service.degraded;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The degraded banner's effect labels, read from the site's own message bundle. */
class DegradedLabelsTest {
    private final DegradedLabels labels = new DegradedLabels("lang/messages");

    @Test
    void listsEveryEffectLabel_butRest() {
        Map<String, String> french = labels.labels(Locale.FRENCH);

        assertThat(french).containsOnlyKeys("app.polling.interval-seconds", "minecraft.friends-sync-interval-ms",
                "app.retry.incomplete-interval-ms");
        assertThat(french.get("app.polling.interval-seconds"))
                .isEqualTo("Le changement de catégorie peut prendre jusqu'à {0}");
    }

    @Test
    void english_hasItsOwnWording() {
        assertThat(labels.labels(Locale.ENGLISH))
                .containsEntry("app.polling.interval-seconds", "Category changes can take up to {0}");
    }

    @Test
    void unknownLocale_fallsBackToDefault() {
        assertThat(labels.labels(Locale.GERMAN))
                .containsEntry("minecraft.friends-sync-interval-ms", "Minecraft friends are synced every {0}");
    }
}
