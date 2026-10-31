package fr.enimaloc.catapult.chat.command.registry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceFunctionTest {

    @Test
    void optionalArgReturnsTheArgWhenPresent() {
        assertThat(ServiceFunction.optionalArg(new Object[]{"a", "b"}, 1)).isEqualTo("b");
    }

    @Test
    void optionalArgReturnsEmptyStringWhenIndexOutOfRange() {
        assertThat(ServiceFunction.optionalArg(new Object[]{"a"}, 1)).isEqualTo("");
        assertThat(ServiceFunction.optionalArg(new Object[]{}, 0)).isEqualTo("");
    }
}
