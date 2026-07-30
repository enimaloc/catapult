package fr.enimaloc.catapult.chat.command.registry.str;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** One test class for the whole str# namespace — each function is a single-purpose one-liner. */
class StrFunctionsTest {

    @Test
    void replace_replacesEveryLiteralOccurrence() {
        StrReplaceFunction fn = new StrReplaceFunction();
        assertThat(fn.namespace()).isEqualTo("str");
        assertThat(fn.name()).isEqualTo("replace");
        assertThat(fn.parameterNames()).containsExactly("text", "search", "replacement");

        assertThat(fn.invoke(null, new Object[]{"a-b-c", "-", "_"})).isEqualTo("a_b_c");
    }

    @Test
    void upper_uppercasesTheWholeString() {
        assertThat(new StrUpperFunction().invoke(null, new Object[]{"Valorant"})).isEqualTo("VALORANT");
    }

    @Test
    void lower_lowercasesTheWholeString() {
        assertThat(new StrLowerFunction().invoke(null, new Object[]{"Valorant"})).isEqualTo("valorant");
    }

    @Test
    void trim_stripsLeadingAndTrailingWhitespace() {
        assertThat(new StrTrimFunction().invoke(null, new Object[]{"  hi  "})).isEqualTo("hi");
    }

    @Test
    void length_returnsTheCharacterCountAsAString() {
        assertThat(new StrLengthFunction().invoke(null, new Object[]{"hello"})).isEqualTo("5");
    }

    @Test
    void substring_withBothStartAndEnd() {
        StrSubstringFunction fn = new StrSubstringFunction();
        assertThat(fn.optionalParameterNames()).containsExactly("end");

        assertThat(fn.invoke(null, new Object[]{"Cyberpunk", "0", "5"})).isEqualTo("Cyber");
    }

    @Test
    void substring_withOnlyStart_defaultsEndToTheStringLength() {
        assertThat(new StrSubstringFunction().invoke(null, new Object[]{"Cyberpunk", "5"})).isEqualTo("punk");
    }

    @Test
    void substring_clampsOutOfRangeIndicesInsteadOfThrowing() {
        StrSubstringFunction fn = new StrSubstringFunction();
        assertThat(fn.invoke(null, new Object[]{"hi", "-5", "999"})).isEqualTo("hi");
        assertThat(fn.invoke(null, new Object[]{"hi", "999", "999"})).isEqualTo("");
    }

    @Test
    void includes_returnsTheDslBooleanSpelling() {
        StrIncludesFunction fn = new StrIncludesFunction();
        assertThat(fn.invoke(null, new Object[]{"Cyberpunk 2077", "punk"})).isEqualTo("true");
        assertThat(fn.invoke(null, new Object[]{"Cyberpunk 2077", "xyz"})).isEqualTo("false");
    }

    @Test
    void indexOf_returnsMinusOneWhenNotFound() {
        StrIndexOfFunction fn = new StrIndexOfFunction();
        assertThat(fn.invoke(null, new Object[]{"Cyberpunk", "punk"})).isEqualTo("5");
        assertThat(fn.invoke(null, new Object[]{"Cyberpunk", "xyz"})).isEqualTo("-1");
    }

    @Test
    void split_onALiteralSeparator() {
        assertThat(new StrSplitFunction().invoke(null, new Object[]{"a,b,c", ","}))
            .isEqualTo(java.util.List.of("a", "b", "c"));
    }

    @Test
    void split_treatsARegexMetacharacterSeparatorLiterally() {
        // '.' must not behave as "any character" — split() quotes the separator.
        assertThat(new StrSplitFunction().invoke(null, new Object[]{"a.b.c", "."}))
            .isEqualTo(java.util.List.of("a", "b", "c"));
    }

    @Test
    void split_onAnEmptySeparatorReturnsTheWholeStringAsOneElement() {
        assertThat(new StrSplitFunction().invoke(null, new Object[]{"abc", ""}))
            .isEqualTo(java.util.List.of("abc"));
    }
}
