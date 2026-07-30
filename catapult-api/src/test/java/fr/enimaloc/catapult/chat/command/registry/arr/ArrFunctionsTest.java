package fr.enimaloc.catapult.chat.command.registry.arr;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** One test class for the whole arr# namespace — each function is a single-purpose one-liner. */
class ArrFunctionsTest {

    @Test
    void push_appendsToAnExistingList() {
        ArrPushFunction fn = new ArrPushFunction();
        assertThat(fn.namespace()).isEqualTo("arr");
        assertThat(fn.name()).isEqualTo("push");
        assertThat(fn.parameterNames()).containsExactly("list", "value");

        assertThat(fn.invoke(null, new Object[]{List.of("a", "b"), "c"})).isEqualTo(List.of("a", "b", "c"));
    }

    @Test
    void push_onTheEmptyStringSentinelStartsANewList() {
        assertThat(new ArrPushFunction().invoke(null, new Object[]{"", "first"})).isEqualTo(List.of("first"));
    }

    @Test
    void push_doesNotMutateTheInputList() {
        List<String> original = List.of("a");
        new ArrPushFunction().invoke(null, new Object[]{original, "b"});
        assertThat(original).containsExactly("a");
    }

    @Test
    void pop_removesTheLastElement() {
        assertThat(new ArrPopFunction().invoke(null, new Object[]{List.of("a", "b", "c")}))
            .isEqualTo(List.of("a", "b"));
    }

    @Test
    void pop_onAnEmptyListStaysEmptyRatherThanThrowing() {
        assertThat(new ArrPopFunction().invoke(null, new Object[]{List.of()})).isEqualTo(List.of());
        assertThat(new ArrPopFunction().invoke(null, new Object[]{""})).isEqualTo(List.of());
    }

    @Test
    void join_withASeparator() {
        assertThat(new ArrJoinFunction().invoke(null, new Object[]{List.of("a", "b", "c"), ", "}))
            .isEqualTo("a, b, c");
    }

    @Test
    void length_returnsTheElementCountAsAString() {
        assertThat(new ArrLengthFunction().invoke(null, new Object[]{List.of("a", "b")})).isEqualTo("2");
        assertThat(new ArrLengthFunction().invoke(null, new Object[]{""})).isEqualTo("0");
    }

    @Test
    void at_returnsTheElementAtAValidIndex() {
        assertThat(new ArrAtFunction().invoke(null, new Object[]{List.of("a", "b", "c"), "1"})).isEqualTo("b");
    }

    @Test
    void at_returnsEmptyStringForAnOutOfRangeOrNonNumericIndex() {
        ArrAtFunction fn = new ArrAtFunction();
        assertThat(fn.invoke(null, new Object[]{List.of("a"), "5"})).isEqualTo("");
        assertThat(fn.invoke(null, new Object[]{List.of("a"), "-1"})).isEqualTo("");
        assertThat(fn.invoke(null, new Object[]{List.of("a"), "not-a-number"})).isEqualTo("");
    }

    @Test
    void first_andLast() {
        assertThat(new ArrFirstFunction().invoke(null, new Object[]{List.of("a", "b", "c")})).isEqualTo("a");
        assertThat(new ArrLastFunction().invoke(null, new Object[]{List.of("a", "b", "c")})).isEqualTo("c");
    }

    @Test
    void first_andLast_returnEmptyStringForAnEmptyList() {
        assertThat(new ArrFirstFunction().invoke(null, new Object[]{""})).isEqualTo("");
        assertThat(new ArrLastFunction().invoke(null, new Object[]{""})).isEqualTo("");
    }

    @Test
    void contains_stringComparesEachElement() {
        ArrContainsFunction fn = new ArrContainsFunction();
        assertThat(fn.invoke(null, new Object[]{List.of("a", "b"), "b"})).isEqualTo("true");
        assertThat(fn.invoke(null, new Object[]{List.of("a", "b"), "z"})).isEqualTo("false");
    }

    @Test
    void reverse_doesNotMutateTheInputList() {
        List<String> original = List.of("a", "b", "c");
        Object result = new ArrReverseFunction().invoke(null, new Object[]{original});
        assertThat(result).isEqualTo(List.of("c", "b", "a"));
        assertThat(original).containsExactly("a", "b", "c");
    }
}
