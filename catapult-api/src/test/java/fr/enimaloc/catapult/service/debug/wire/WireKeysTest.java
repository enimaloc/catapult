package fr.enimaloc.catapult.service.debug.wire;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WireKeysTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static JsonNode json(String text) {
        return MAPPER.readTree(text);
    }

    @Test
    void numericLeafGivesIdAndInvitationKeys() {
        assertThat(WireKeys.of(json("{\"1\":42}"))).contains("id:42", "inv:42");
        assertThat(WireKeys.of(json("{\"1\":\"42\"}"))).contains("id:42", "inv:42");
    }

    @Test
    void stringLeafGivesLowercaseTagKey() {
        assertThat(WireKeys.of(json("{\"1\":{\"2\":\"Foo#1234\"}}"))).contains("tag:foo#1234");
    }

    @Test
    void nameAndTagFieldsAreRecombined() {
        Set<String> keys = WireKeys.of(json("{\"2\":\"Foo\",\"3\":\"1234\"}"));

        assertThat(keys).contains("tag:foo#1234");
    }

    @Test
    void arraysAreWalked() {
        assertThat(WireKeys.of(json("{\"1\":[7,{\"2\":\"Bar#1\"}]}"))).contains("id:7", "tag:bar#1");
    }

    @Test
    void exactMatchOnly() {
        assertThat(WireKeys.concerns(WireKeys.of(json("{\"1\":12345}"))).test("id:1234")).isFalse();
        assertThat(WireKeys.concerns(WireKeys.of(json("{\"1\":12345}"))).test("id:12345")).isTrue();
    }

    @Test
    void rawConcernsFindsAVarintId() {
        byte[] raw = {0x08, (byte) 0x96, 0x01};

        assertThat(WireKeys.rawConcerns(raw).test("id:150")).isTrue();
        assertThat(WireKeys.rawConcerns(raw).test("inv:150")).isTrue();
        assertThat(WireKeys.rawConcerns(raw).test("id:151")).isFalse();
    }

    @Test
    void rawConcernsFindsATagCaseInsensitively() {
        byte[] raw = "xxFoo#1234yy".getBytes();

        assertThat(WireKeys.rawConcerns(raw).test("tag:foo#1234")).isTrue();
        assertThat(WireKeys.rawConcerns(raw).test("tag:other#1")).isFalse();
        assertThat(WireKeys.rawConcerns(raw).test("unprefixed")).isFalse();
    }
}
