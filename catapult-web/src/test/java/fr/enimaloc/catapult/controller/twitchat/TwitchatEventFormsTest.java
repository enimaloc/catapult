package fr.enimaloc.catapult.controller.twitchat;

import fr.enimaloc.catapult.common.dto.twitchat.TwitchatEventActionDto;
import fr.enimaloc.catapult.common.twitchat.TwitchatCatalog;
import fr.enimaloc.catapult.dto.integration.IntegrationNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class TwitchatEventFormsTest {

    private final TwitchatEventForms forms = new TwitchatEventForms(TwitchatCatalog.load());

    /** Every node of a tree, depth first. */
    private static List<IntegrationNode> flatten(IntegrationNode node) {
        List<IntegrationNode> all = new ArrayList<>(List.of(node));
        Stream<IntegrationNode> children = switch (node) {
            case IntegrationNode.Container c -> c.children().stream();
            case IntegrationNode.Repeater r -> Stream.concat(r.rows().stream().flatMap(List::stream), r.template().stream());
            case IntegrationNode.Collapse c -> c.items().stream().flatMap(i -> i.children().stream());
            default -> Stream.empty();
        };
        children.forEach(child -> all.addAll(flatten(child)));
        return all;
    }

    private static <T extends IntegrationNode> List<T> of(IntegrationNode root, Class<T> type) {
        return flatten(root).stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static IntegrationNode byField(IntegrationNode root, String field) {
        return flatten(root).stream().filter(n -> field.equals(n.attrs().field())).findFirst().orElseThrow();
    }

    @Test
    void chatMessageCard_fillsItsFieldsFromTheData() {
        IntegrationNode card = forms.payloadCard("CATEGORY_CHANGED_BY_CATAPULT", new TwitchatEventActionDto("CUSTOM_CHAT_MESSAGE", "FORM",
                "{\"message\":\"Hi\",\"style\":\"highlight\",\"user\":{\"name\":\"Bot\"},\"actions\":[{\"label\":\"Revert\",\"actionType\":\"url\",\"url\":\"{{baseUrl}}/widget/twitchat/action/{{action:REVERT_CATEGORY}}\"}]}",
                true));

        assertThat(((IntegrationNode.TextField) byField(card, "message")).value()).isEqualTo("Hi");
        assertThat(((IntegrationNode.Select) byField(card, "style")).value()).isEqualTo("highlight");
        assertThat(((IntegrationNode.TextField) byField(card, "user.name")).value()).isEqualTo("Bot");
        IntegrationNode.Repeater buttons = (IntegrationNode.Repeater) byField(card, "actions");
        assertThat(buttons.rows()).hasSize(1);
        // The Catapult action helper shows the action the URL points at, among this event's.
        IntegrationNode.Select target = of(card, IntegrationNode.Select.class).stream()
                .filter(s -> s.attrs().className() != null && s.attrs().className().contains("twitchat-catapult-action"))
                .findFirst().orElseThrow();
        assertThat(target.value()).isEqualTo("REVERT_CATEGORY");
        assertThat(target.options()).extracting(IntegrationNode.Select.Option::value).containsExactly("", "REVERT_CATEGORY", "DISABLE_BOT");
    }

    @Test
    void card_carriesItsActionModeAndProtocolNames() {
        IntegrationNode card = forms.payloadCard("STREAM_STARTED", new TwitchatEventActionDto("EXECUTE_TRIGGER", "FORM", "{\"id\":\"t-1\"}", false));
        List<IntegrationNode.Hidden> hidden = of(card, IntegrationNode.Hidden.class);
        assertThat(hidden).extracting(IntegrationNode.Hidden::value)
                .contains("EXECUTE_TRIGGER", "FORM", "{\"stable\":\"EXECUTE_TRIGGER\",\"beta\":\"SET_EXECUTE_TRIGGER\"}");
        assertThat(of(card, IntegrationNode.Switch.class)).anySatisfy(s -> assertThat(s.checked()).isFalse());
    }

    @Test
    void liveSelect_keepsAStoredValueTwitchatNoLongerLists() {
        IntegrationNode card = forms.payloadCard("STREAM_STARTED", new TwitchatEventActionDto("EXECUTE_TRIGGER", "FORM", "{\"id\":\"gone\"}", true));
        IntegrationNode.Select trigger = (IntegrationNode.Select) byField(card, "id");
        assertThat(trigger.value()).isEqualTo("gone");
        assertThat(trigger.options()).extracting(IntegrationNode.Select.Option::value).containsExactly("gone");
        assertThat(trigger.source()).contains("GET_TRIGGER_LIST");
    }

    @Test
    void rawCard_hidesTheFormAndShowsTheJson() {
        IntegrationNode card = forms.payloadCard("STREAM_STARTED", new TwitchatEventActionDto("SEND_MESSAGE", "RAW", "{\"message\":\"x\"}", true));
        IntegrationNode.Container form = of(card, IntegrationNode.Container.class).stream()
                .filter(c -> c.attrs().className().contains("twitchat-payload-form")).findFirst().orElseThrow();
        assertThat(form.attrs().className()).contains("hidden");
        assertThat(of(card, IntegrationNode.TextField.class)).anySatisfy(t ->
                assertThat(t.attrs().className()).contains("twitchat-payload-raw-json"));
    }

    @Test
    void newPayload_startsFromTheFieldDefaults() {
        IntegrationNode card = forms.payloadCard("STREAM_STARTED", forms.newPayload("COUNTER_ADD"));
        assertThat(((IntegrationNode.Select) byField(card, "action")).value()).isEqualTo("ADD");
        assertThat(((IntegrationNode.TextField) byField(card, "value")).value()).isEqualTo("1");
    }

    @Test
    void eventsSection_listsEveryEvent_withItsPayloadCount() {
        IntegrationNode section = forms.eventsSection(Map.of("STREAM_STARTED", List.of(
                new TwitchatEventActionDto("SEND_MESSAGE", "FORM", "{\"message\":\"x\"}", true))));
        IntegrationNode.Collapse collapse = (IntegrationNode.Collapse) section;
        assertThat(collapse.items()).extracting(IntegrationNode.Collapse.Item::value).containsExactly(
                "CATEGORY_CHANGED_BY_CATAPULT", "CATEGORY_CHANGED_MANUALLY", "STREAM_STARTED", "BOT_ENABLED", "BOT_DISABLED", "NO_GAME_DETECTED");
        assertThat(collapse.items().get(2).text().args()).containsExactly(1);
        assertThat(collapse.items().get(5).text().args()).containsExactly(0);
    }
}
