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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                "CATEGORY_CHANGED_BY_CATAPULT", "CATEGORY_CHANGED_MANUALLY", "STREAM_STARTED", "BOT_ENABLED", "BOT_DISABLED", "NO_GAME_DETECTED", "NOTIFICATION");
        assertThat(collapse.items().get(2).text().args()).containsExactly(1);
        assertThat(collapse.items().get(5).text().args()).containsExactly(0);
    }

    @Test
    void notificationEvent_documentsItsVariables_andOffersNoButtonAction() {
        IntegrationNode.Collapse collapse = (IntegrationNode.Collapse) forms.eventsSection(Map.of());
        IntegrationNode.Collapse.Item notification = collapse.items().get(6);

        assertThat(notification.children().get(1)).isInstanceOf(IntegrationNode.Text.class);
        assertThat(((IntegrationNode.Text) notification.children().get(1)).text().args().get(0).toString())
                .contains("{{title}}", "{{body}}", "{{ctaUrl}}", "{{ctaLabel}}");
        assertThat(fr.enimaloc.catapult.common.dto.twitchat.TwitchatEvents.ACTIONS.get("NOTIFICATION")).isEmpty();
    }

    @Test
    void cardOfAnActionGoneFromTheCatalog_keepsItsRawJsonOnly_underItsStoredKey() {
        IntegrationNode card = forms.payloadCard("STREAM_STARTED", new TwitchatEventActionDto("GONE_ACTION", "FORM", "{\"a\":1}", true));

        assertThat(of(card, IntegrationNode.Text.class)).filteredOn(t -> t.attrs().className().contains("twitchat-payload-title")).singleElement().satisfies(t -> {
            assertThat(t.attrs().className()).contains("twitchat-payload-title");
            assertThat(t.text().literal()).isEqualTo("GONE_ACTION");
        });
        assertThat(of(card, IntegrationNode.Hidden.class)).extracting(IntegrationNode.Hidden::value).contains("GONE_ACTION", "RAW");
        assertThat(of(card, IntegrationNode.Container.class)).filteredOn(c -> c.attrs().className().contains("twitchat-payload-form"))
                .singleElement().satisfies(form -> assertThat(form.attrs().className()).contains("hidden"));
        assertThat(of(card, IntegrationNode.Container.class)).filteredOn(c -> c.attrs().className().contains("twitchat-payload-raw"))
                .singleElement().satisfies(rawPart -> assertThat(rawPart.attrs().className()).doesNotContain("hidden"));
        assertThat(of(card, IntegrationNode.Button.class)).filteredOn(b -> b.attrs().className().contains("twitchat-payload-raw-btn"))
                .singleElement().satisfies(toggle -> assertThat(toggle.attrs().className()).contains("hidden"));
        assertThat(of(card, IntegrationNode.TextField.class)).anySatisfy(t -> assertThat(t.value()).asString().contains("\"a\" : 1"));
    }

    @Test
    void eventsSection_survivesAPayloadWhoseActionLeftTheCatalog() {
        assertThat(forms.eventsSection(Map.of("STREAM_STARTED", List.of(
                new TwitchatEventActionDto("GONE_ACTION", "FORM", "{}", true))))).isNotNull();
    }

    private static boolean hidden(IntegrationNode node) {
        return node.attrs().className() != null && node.attrs().className().contains("hidden");
    }

    private static IntegrationNode inRow(List<IntegrationNode> row, String field) {
        return row.stream().filter(n -> field.equals(n.attrs().field())).findFirst().orElseThrow();
    }

    @Test
    void buttonRow_showsOnlyTheFieldsOfItsType_theTemplateThoseOfTheDefaultType() {
        IntegrationNode card = forms.payloadCard("STREAM_STARTED", new TwitchatEventActionDto("CUSTOM_CHAT_MESSAGE", "FORM",
                "{\"message\":\"Hi\",\"actions\":[{\"label\":\"Intro\",\"actionType\":\"trigger\",\"triggerId\":\"t-1\"}]}", true));
        IntegrationNode.Repeater buttons = (IntegrationNode.Repeater) byField(card, "actions");

        List<IntegrationNode> row = buttons.rows().getFirst();
        assertThat(hidden(inRow(row, "triggerId"))).isFalse();
        assertThat(((IntegrationNode.Select) inRow(row, "triggerId")).source()).contains("TRIGGERS_GET_ALL");
        assertThat(hidden(inRow(row, "url"))).isTrue();
        assertThat(hidden(inRow(row, "message"))).isTrue();
        assertThat(inRow(row, "url").attrs().showIf()).isEqualTo("{\"path\":\"actionType\",\"values\":[\"url\"]}");

        assertThat(hidden(inRow(buttons.template(), "url"))).isFalse();
        assertThat(hidden(inRow(buttons.template(), "triggerId"))).isTrue();
    }

    @Test
    void switchWithoutAStoredValue_showsItsDefault() {
        IntegrationNode stored = forms.payloadCard("STREAM_STARTED", new TwitchatEventActionDto("CUSTOM_CHAT_MESSAGE", "FORM", "{\"message\":\"Hi\"}", true));
        assertThat(((IntegrationNode.Switch) byField(stored, "canClose")).checked()).isTrue();
        assertThat(((IntegrationNode.Switch) byField(stored, "todayFirst")).checked()).isFalse();

        IntegrationNode closed = forms.payloadCard("STREAM_STARTED", new TwitchatEventActionDto("CUSTOM_CHAT_MESSAGE", "FORM", "{\"canClose\":false}", true));
        assertThat(((IntegrationNode.Switch) byField(closed, "canClose")).checked()).isFalse();
    }

    @Test
    void dataRequests_areNeitherOffered_norStartable() {
        IntegrationNode.Select add = of(forms.eventsSection(Map.of()), IntegrationNode.Select.class).stream()
                .filter(s -> s.attrs().className() != null && s.attrs().className().contains("twitchat-add-action"))
                .findFirst().orElseThrow();
        assertThat(add.options()).extracting(IntegrationNode.Select.Option::value)
                .contains("SEND_MESSAGE", "TOGGLE_TRIGGER").doesNotContain("TRIGGERS_GET_ALL", "GET_TRIGGER_LIST", "ENDING_CREDITS_PRESENCE");
        assertThatThrownBy(() -> forms.newPayload("TRIGGERS_GET_ALL")).isInstanceOf(TwitchatEventForms.UnknownAction.class);
    }

    @Test
    void listField_rendersOneTextFieldPerItem() {
        IntegrationNode card = forms.payloadCard("STREAM_STARTED", new TwitchatEventActionDto("CHAT_POLL_START", "FORM",
                "{\"title\":\"Best?\",\"choices\":[\"A\",\"B\"]}", true));
        IntegrationNode.Repeater choices = (IntegrationNode.Repeater) byField(card, "choices");
        assertThat(choices.attrs().className()).contains("list-repeater");
        assertThat(choices.rows()).extracting(row -> ((IntegrationNode.TextField) row.getFirst()).value()).containsExactly("A", "B");
        assertThat(((IntegrationNode.TextField) choices.template().getFirst()).value()).isNull();
    }

    @Test
    void choiceField_selectsItsJsonLiteral() {
        IntegrationNode card = forms.payloadCard("STREAM_STARTED", new TwitchatEventActionDto("CLICKABLE_AREA_STATE", "FORM",
                "{\"id\":\"a\",\"state\":\"toggle\"}", true));
        IntegrationNode.Select state = (IntegrationNode.Select) byField(card, "state");
        assertThat(state.attrs().className()).contains("json-field");
        assertThat(state.value()).isEqualTo("\"toggle\"");
        assertThat(state.options()).extracting(IntegrationNode.Select.Option::value).containsExactly("true", "false", "\"toggle\"");
    }

    @Test
    void optionalSelect_canBeEmptied_aRequiredOneCannot() {
        IntegrationNode card = forms.payloadCard("STREAM_STARTED", forms.newPayload("CUSTOM_CHAT_MESSAGE"));
        IntegrationNode.Select icon = (IntegrationNode.Select) byField(card, "icon");
        assertThat(icon.options().getFirst().value()).isEmpty();
        assertThat(icon.options()).extracting(IntegrationNode.Select.Option::value).contains("live", "bot");
        IntegrationNode trigger = forms.payloadCard("STREAM_STARTED", forms.newPayload("TOGGLE_TRIGGER"));
        assertThat(((IntegrationNode.Select) byField(trigger, "triggerId")).options()).isEmpty();
    }

    @Test
    void iconSelect_showsEveryTwitchatIconWithItsPicture_thePickedOneBesideIt() {
        TwitchatEventActionDto payload = new TwitchatEventActionDto("CUSTOM_CHAT_MESSAGE", "FORM",
                "{\"message\":\"x\",\"icon\":\"add\"}", true);
        IntegrationNode.Select icon = (IntegrationNode.Select) byField(forms.payloadCard("STREAM_STARTED", payload), "icon");

        assertThat(icon.hasIcons()).isTrue();
        assertThat(icon.pickedIcon()).startsWith("/assets/ext/twitchat/").endsWith("/add.svg");
        // More than the API's typing lists: every icon Twitchat's folder holds.
        assertThat(icon.options()).extracting(IntegrationNode.Select.Option::value).contains("bluesky", "folder");
        // The "none" option has no picture.
        assertThat(icon.options().getFirst().icon()).isNull();
        // An icon the stable Twitchat lacks says so; one both have names no branch.
        assertThat(icon.hasBranchOptions()).isTrue();
        assertThat(option(icon, "bluesky").protocols()).containsExactly("beta");
        assertThat(option(icon, "add").protocols()).isNull();
    }

    private static IntegrationNode.Select.Option option(IntegrationNode.Select select, String value) {
        return select.options().stream().filter(o -> o.value().equals(value)).findFirst().orElseThrow();
    }

    @Test
    void selectWithoutPictures_hasNoIcons() {
        IntegrationNode card = forms.payloadCard("STREAM_STARTED", forms.newPayload("CUSTOM_CHAT_MESSAGE"));
        IntegrationNode.Select style = (IntegrationNode.Select) byField(card, "style");
        assertThat(style.hasIcons()).isFalse();
        assertThat(style.hasBranchOptions()).isFalse();
        assertThat(style.pickedIcon()).isNull();
    }

    @Test
    void newPayload_ofAnUnknownAction_isRefused() {
        assertThatThrownBy(() -> forms.newPayload("GONE_ACTION")).isInstanceOf(TwitchatEventForms.UnknownAction.class);
    }
}
