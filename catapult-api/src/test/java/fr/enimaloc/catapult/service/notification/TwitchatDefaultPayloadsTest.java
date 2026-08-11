package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.domain.TwitchatActionType;
import fr.enimaloc.catapult.domain.TwitchatNotificationEventType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TwitchatDefaultPayloadsTest {

    @Test
    void defaults_coverAllFiveEventTypes() {
        assertThat(TwitchatDefaultPayloads.DEFAULTS.keySet())
                .containsExactlyInAnyOrder(TwitchatNotificationEventType.values());
    }

    @Test
    void categoryChangedByCatapult_matchesCurrentHardcodedCopy() {
        var payload = TwitchatDefaultPayloads.DEFAULTS.get(TwitchatNotificationEventType.CATEGORY_CHANGED_BY_CATAPULT);

        assertThat(payload.message()).isEqualTo("Catapult a changé la catégorie en {{gameName}}.");
        assertThat(payload.icon()).isEqualTo("change");
        assertThat(payload.authorName()).isEqualTo("Catapult");
        assertThat(payload.actions().get(TwitchatActionType.REVERT_CATEGORY).label()).isEqualTo("Revert");
        assertThat(payload.actions().get(TwitchatActionType.DISABLE_BOT).theme()).isEqualTo("alert");
    }

    @Test
    void botEnabled_matchesCurrentHardcodedCopy() {
        var payload = TwitchatDefaultPayloads.DEFAULTS.get(TwitchatNotificationEventType.BOT_ENABLED);

        assertThat(payload.message()).isEqualTo("Le bot a été activé.");
        assertThat(payload.icon()).isEqualTo("online");
        assertThat(payload.actions().get(TwitchatActionType.DISABLE_BOT).theme()).isEqualTo("secondary");
    }
}
