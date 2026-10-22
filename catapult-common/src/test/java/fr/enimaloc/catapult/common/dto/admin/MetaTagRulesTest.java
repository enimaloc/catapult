package fr.enimaloc.catapult.common.dto.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static fr.enimaloc.catapult.common.dto.admin.MetaTagAttribute.NAME;
import static fr.enimaloc.catapult.common.dto.admin.MetaTagAttribute.PROPERTY;
import static org.assertj.core.api.Assertions.assertThat;

class MetaTagRulesTest {

    private static String problem(MetaTagAttribute attribute, String key, String content) {
        return MetaTagRules.problem(new MetaTagRequest(attribute, key, content));
    }

    @Test
    void aPlainNameAndOneLineContent_isValid() {
        assertThat(problem(NAME, "google-site-verification", "abc123")).isNull();
        assertThat(problem(PROPERTY, "og:image", "https://catapult.test/card.png")).isNull();
        assertThat(problem(NAME, "a", "x".repeat(MetaTagRules.MAX_CONTENT))).isNull();
    }

    @Test
    void noRequestOrNoAttribute_isMissingTheAttribute() {
        assertThat(MetaTagRules.problem(null)).isEqualTo("Missing attribute");
        assertThat(problem(null, "author", "x")).isEqualTo("Missing attribute");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1author", "-x", "with space", "quote\"", "<script>", "é"})
    void aKeyThatIsNotAPlainName_isInvalid(String key) {
        assertThat(problem(NAME, key, "x")).isEqualTo("Invalid key");
    }

    @Test
    void aMissingOrTooLongKey_isInvalid() {
        assertThat(problem(NAME, null, "x")).isEqualTo("Invalid key");
        assertThat(problem(NAME, "a".repeat(MetaTagRules.MAX_KEY), "x")).isNull();
        assertThat(problem(NAME, "a".repeat(MetaTagRules.MAX_KEY + 1), "x")).isEqualTo("Invalid key");
    }

    @ParameterizedTest
    @ValueSource(strings = {"viewport", "Description", "OG:TITLE", "twitter:card", "sentry-trace", "baggage", "DarkReader-Lock"})
    void aKeyTheSiteWritesItself_isReserved_whateverItsCase(String key) {
        assertThat(problem(PROPERTY, key, "x")).isEqualTo("Reserved key");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "two\nlines", "carriage\rreturn", "tab\there", "line separator",
            "paragraph separator", "nul\u0000"})
    void aContentThatIsBlankOrBreaksTheLine_isInvalid(String content) {
        assertThat(problem(NAME, "author", content)).isEqualTo("Invalid content");
    }

    @Test
    void aMissingOrTooLongContent_isInvalid() {
        assertThat(problem(NAME, "author", null)).isEqualTo("Invalid content");
        assertThat(problem(NAME, "author", "x".repeat(MetaTagRules.MAX_CONTENT + 1))).isEqualTo("Invalid content");
    }

    @Test
    void theAttributeIsWrittenAsInTheTag() {
        assertThat(NAME.html()).isEqualTo("name");
        assertThat(PROPERTY.html()).isEqualTo("property");
    }
}
