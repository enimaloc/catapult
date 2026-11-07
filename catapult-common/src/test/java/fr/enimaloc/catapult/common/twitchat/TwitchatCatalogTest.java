package fr.enimaloc.catapult.common.twitchat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TwitchatCatalogTest {

    private final TwitchatCatalog catalog = TwitchatCatalog.load();

    @Test
    void loadsTheGeneratedCatalog_priorityActionsFirst() {
        assertThat(catalog.version()).isEqualTo(1);
        assertThat(catalog.actions()).extracting(TwitchatCatalog.Action::key)
                .startsWith("CUSTOM_CHAT_MESSAGE", "EXECUTE_TRIGGER", "SEND_MESSAGE", "COUNTER_ADD");
    }

    @Test
    void readsNamesSourcesAndRepeaterRows() {
        TwitchatCatalog.Action trigger = catalog.action("EXECUTE_TRIGGER").orElseThrow();
        assertThat(trigger.names()).containsEntry("stable", "EXECUTE_TRIGGER").containsEntry("beta", "SET_EXECUTE_TRIGGER");
        assertThat(trigger.fields().getFirst().source()).containsEntry("beta", "GET_TRIGGER_LIST");

        TwitchatCatalog.Field buttons = catalog.action("CUSTOM_CHAT_MESSAGE").orElseThrow().fields().stream()
                .filter(f -> "actions".equals(f.path())).findFirst().orElseThrow();
        assertThat(buttons.type()).isEqualTo("repeater");
        assertThat(buttons.fields()).extracting(TwitchatCatalog.Field::type).contains("catapult-action", "select");
    }

    @Test
    void unknownKey_isEmpty() {
        assertThat(catalog.action("NOPE")).isEmpty();
    }
}
