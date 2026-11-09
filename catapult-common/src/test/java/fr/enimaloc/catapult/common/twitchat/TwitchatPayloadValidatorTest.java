package fr.enimaloc.catapult.common.twitchat;

import fr.enimaloc.catapult.common.dto.twitchat.TwitchatEventActionDto;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatValidationError;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TwitchatPayloadValidatorTest {

    private final TwitchatPayloadValidator validator = new TwitchatPayloadValidator(TwitchatCatalog.load());

    private List<TwitchatValidationError> validate(String action, String mode, String json) {
        return validator.validate(List.of(new TwitchatEventActionDto(action, mode, json, true)));
    }

    @Test
    void validChatMessage_hasNoError() {
        assertThat(validate("CUSTOM_CHAT_MESSAGE", "FORM", """
                {"message":"Hi {{gameName}}","style":"highlight","user":{"name":"Catapult"},
                 "actions":[{"label":"Revert","actionType":"url","url":"{{baseUrl}}/x/{{action:REVERT_CATEGORY}}"}]}
                """)).isEmpty();
    }

    @Test
    void unknownAction_badMode_andNonObjectJson_areReported() {
        assertThat(validate("NOPE", "FORM", "{}")).containsExactly(new TwitchatValidationError(0, "action", "Unknown Twitchat action: NOPE"));
        assertThat(validate("SEND_MESSAGE", "WHATEVER", "{}")).extracting(TwitchatValidationError::field).containsExactly("mode");
        assertThat(validate("SEND_MESSAGE", "RAW", "[1]")).extracting(TwitchatValidationError::field).containsExactly("dataJson");
        assertThat(validate("SEND_MESSAGE", "RAW", "{oops")).extracting(TwitchatValidationError::field).containsExactly("dataJson");
    }

    @Test
    void rawMode_skipsTheSchema() {
        assertThat(validate("SEND_MESSAGE", "RAW", "{\"anything\":42}")).isEmpty();
    }

    @Test
    void formMode_checksRequiredTypesChoicesAndRows() {
        List<TwitchatValidationError> errors = validate("CUSTOM_CHAT_MESSAGE", "FORM", """
                {"message":"  ","style":"neon","user":{"name":3},
                 "actions":[{"label":"ok","actionType":"url"},{"actionType":"url"}, 7]}
                """);
        assertThat(errors).extracting(TwitchatValidationError::field).containsExactly(
                "message", "style", "user.name", "actions[1].label", "actions[2]");
    }

    @Test
    void aDataRequest_isRefused_itWouldDoNothing() {
        assertThat(validate("TRIGGERS_GET_ALL", "RAW", "{}"))
                .containsExactly(new TwitchatValidationError(0, "action", "Only returns data, does nothing: TRIGGERS_GET_ALL"));
    }

    @Test
    void choiceField_comparesItsJsonLiterals() {
        assertThat(validate("CLICKABLE_AREA_STATE", "FORM", "{\"id\":\"a\",\"state\":true}")).isEmpty();
        assertThat(validate("CLICKABLE_AREA_STATE", "FORM", "{\"id\":\"a\",\"state\":\"toggle\"}")).isEmpty();
        assertThat(validate("CLICKABLE_AREA_STATE", "FORM", "{\"id\":\"a\",\"state\":\"true\"}"))
                .extracting(TwitchatValidationError::field).containsExactly("state");
    }

    @Test
    void listField_wantsTexts() {
        String poll = "{\"title\":\"t\",\"duration\":60,\"maxVotePerUser\":1,\"choices\":%s}";
        assertThat(validate("CHAT_POLL_START", "FORM", poll.formatted("[\"a\",\"b\"]"))).isEmpty();
        assertThat(validate("CHAT_POLL_START", "FORM", poll.formatted("[1]")))
                .extracting(TwitchatValidationError::field).containsExactly("choices");
    }

    @Test
    void numberField_acceptsAPlaceholder() {
        // COUNTER_ADD.value is "text" (an arithmetic expression), so use a generated number field instead:
        TwitchatCatalog catalog = new TwitchatCatalog(1, List.of(new TwitchatCatalog.Action("X", Map.of(), "", null, "action", "generated",
                List.of(new TwitchatCatalog.Field("n", "number", "n", null, true, null, null, null, null, null)))));
        TwitchatPayloadValidator v = new TwitchatPayloadValidator(catalog);
        assertThat(v.validate(List.of(new TwitchatEventActionDto("X", "FORM", "{\"n\":\"{{count}}\"}", true)))).isEmpty();
        assertThat(v.validate(List.of(new TwitchatEventActionDto("X", "FORM", "{\"n\":\"three\"}", true))))
                .extracting(TwitchatValidationError::field).containsExactly("n");
    }

    @Test
    void aRequiredFieldTheFormHides_isNotRequired() {
        TwitchatCatalog catalog = new TwitchatCatalog(1, List.of(new TwitchatCatalog.Action("X", Map.of(), "", null, "action", "generated",
                List.of(new TwitchatCatalog.Field("kind", "select", "kind", null, false,
                                List.of(new TwitchatCatalog.Option("url", "url", null), new TwitchatCatalog.Option("text", "text", null)),
                                null, null, null, null),
                        new TwitchatCatalog.Field("url", "text", "url", null, true, null, null, null, null,
                                new TwitchatCatalog.Condition("kind", List.of("url")))))));
        TwitchatPayloadValidator v = new TwitchatPayloadValidator(catalog);

        assertThat(v.validate(List.of(new TwitchatEventActionDto("X", "FORM", "{\"kind\":\"text\"}", true)))).isEmpty();
        assertThat(v.validate(List.of(new TwitchatEventActionDto("X", "FORM", "{}", true)))).isEmpty();
        assertThat(v.validate(List.of(new TwitchatEventActionDto("X", "FORM", "{\"kind\":\"url\"}", true))))
                .extracting(TwitchatValidationError::field).containsExactly("url");
    }

    @Test
    void aConditionWithoutValues_neverShowsItsField() {
        assertThat(new TwitchatCatalog.Condition("kind", null).values()).isEmpty();
    }

    @Test
    void liveSelect_acceptsAnyValue() {
        assertThat(validate("EXECUTE_TRIGGER", "FORM", "{\"id\":\"deleted-trigger-id\"}")).isEmpty();
    }

    @Test
    void errorsCarryThePayloadIndex() {
        List<TwitchatValidationError> errors = validator.validate(List.of(
                new TwitchatEventActionDto("SEND_MESSAGE", "FORM", "{\"message\":\"ok\"}", true),
                new TwitchatEventActionDto("SEND_MESSAGE", "FORM", "{}", true)));
        assertThat(errors).containsExactly(new TwitchatValidationError(1, "message", "Required"));
    }

    private List<TwitchatValidationError> outsideForm(String action, String json) {
        return validator.outsideForm(3, TwitchatCatalog.load().action(action).orElseThrow().fields(), validator.parse(json));
    }

    @Test
    void outsideForm_isEmptyForWhatTheFormHolds() {
        assertThat(outsideForm("CUSTOM_CHAT_MESSAGE", """
                {"message":"Hi","style":"highlight","icon":"live","user":{"name":"Catapult"},
                 "actions":[{"label":"Revert","theme":"primary","actionType":"url","url":"https://x","message":"!x"}]}
                """)).isEmpty();
    }

    @Test
    void outsideForm_listsTheKeysTheFormWouldDrop_atAnyDepth_andInRows() {
        assertThat(outsideForm("CUSTOM_CHAT_MESSAGE", """
                {"message":"Hi","sticker":"q","user":{"name":"Catapult","badge":"mod"},
                 "actions":[{"label":"a"},{"label":"b","extra":1}]}
                """)).containsExactly(
                new TwitchatValidationError(3, "sticker", "Not in the form"),
                new TwitchatValidationError(3, "user.badge", "Not in the form"),
                new TwitchatValidationError(3, "actions[1].extra", "Not in the form"));
    }

    @Test
    void outsideForm_reportsAScalarWhereTheFormExpectsAnObject() {
        assertThat(outsideForm("CUSTOM_CHAT_MESSAGE", "{\"message\":\"Hi\",\"user\":\"Bot\",\"icon\":null}"))
                .containsExactly(new TwitchatValidationError(3, "user", "Not in the form"));
    }

    @Test
    void outsideForm_aJsonFieldCoversItsWholeSubtree() {
        assertThat(outsideForm("SHOW_CLIP",
                "{\"clip\":{\"id\":\"a\",\"any\":{\"deep\":[1]}},\"params\":{\"position\":\"m\"}}")).isEmpty();
    }
}
