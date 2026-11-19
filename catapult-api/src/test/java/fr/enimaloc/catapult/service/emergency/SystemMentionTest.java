package fr.enimaloc.catapult.service.emergency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SystemMentionTest {

    private static final String SYSTEM_ID = "sys-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode message(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    void parse_firstFragmentMentionsSystem_returnsCommand() throws Exception {
        JsonNode message = message("""
                {"text": "@bot emergency steam", "fragments": [
                  {"type": "mention", "text": "@bot", "mention": {"user_id": "sys-1"}},
                  {"type": "text", "text": " emergency steam"}]}""");

        var mention = SystemMention.parse(message, SYSTEM_ID);

        assertThat(mention).contains(new SystemMention("emergency", java.util.List.of("steam")));
    }

    @Test
    void parse_mentionNotFirst_isEmpty() throws Exception {
        JsonNode message = message("""
                {"fragments": [
                  {"type": "text", "text": "hey "},
                  {"type": "mention", "text": "@bot", "mention": {"user_id": "sys-1"}},
                  {"type": "text", "text": " off"}]}""");

        assertThat(SystemMention.parse(message, SYSTEM_ID)).isEmpty();
    }

    @Test
    void parse_otherAccountMentioned_isEmpty() throws Exception {
        JsonNode message = message("""
                {"fragments": [
                  {"type": "mention", "text": "@someone", "mention": {"user_id": "other"}},
                  {"type": "text", "text": " off"}]}""");

        assertThat(SystemMention.parse(message, SYSTEM_ID)).isEmpty();
        assertThat(SystemMention.parse(message, null)).isEmpty();
    }

    @Test
    void parse_uppercaseCommand_isLowercased() throws Exception {
        JsonNode message = message("""
                {"fragments": [
                  {"type": "mention", "text": "@bot", "mention": {"user_id": "sys-1"}},
                  {"type": "text", "text": "  OFF  Now "}]}""");

        var mention = SystemMention.parse(message, SYSTEM_ID);

        assertThat(mention).isPresent();
        assertThat(mention.get().command()).isEqualTo("off");
        assertThat(mention.get().args()).containsExactly("Now");
    }

    @Test
    void parse_mentionAlone_isEmpty() throws Exception {
        JsonNode message = message("""
                {"fragments": [{"type": "mention", "text": "@bot", "mention": {"user_id": "sys-1"}}]}""");

        assertThat(SystemMention.parse(message, SYSTEM_ID)).isEmpty();
    }
}
