package fr.esportline.catapult.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vérifie que l'enum TwitchCcl ne contient que des labels reconnus par l'API Twitch
 * et que les flags editable/non-editable sont corrects.
 */
class TwitchCclTest {

    /** Labels valides selon la doc Twitch PATCH /helix/channels. */
    private static final List<String> TWITCH_VALID_LABEL_IDS = List.of(
        "MatureGame", "ViolentGraphic", "SexualThemes",
        "DrugsIntoxication", "Gambling", "ProfanityVulgarity"
    );

    @Test
    void allEnumValues_areValidTwitchLabelIds() {
        for (TwitchCcl ccl : TwitchCcl.values()) {
            assertThat(TWITCH_VALID_LABEL_IDS)
                .as("TwitchCcl.%s is not a valid Twitch CCL label ID", ccl.name())
                .contains(ccl.name());
        }
    }

    @Test
    void matureGame_isNotEditable() {
        assertThat(TwitchCcl.MatureGame.editable).isFalse();
    }

    @Test
    void allLabelsExceptMatureGame_areEditable() {
        for (TwitchCcl ccl : TwitchCcl.values()) {
            if (ccl != TwitchCcl.MatureGame) {
                assertThat(ccl.editable)
                    .as("TwitchCcl.%s should be editable", ccl.name())
                    .isTrue();
            }
        }
    }

    @Test
    void languageBarrier_doesNotExist() {
        var names = Arrays.stream(TwitchCcl.values()).map(Enum::name).toList();
        assertThat(names).doesNotContain("LanguageBarrier");
    }

    @Test
    void drugUse_doesNotExist() {
        var names = Arrays.stream(TwitchCcl.values()).map(Enum::name).toList();
        assertThat(names).doesNotContain("DrugUse");
    }
}
