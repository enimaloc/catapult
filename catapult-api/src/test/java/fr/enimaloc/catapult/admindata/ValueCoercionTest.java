package fr.enimaloc.catapult.admindata;

import fr.enimaloc.catapult.chat.ChatCommandEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValueCoercionTest {

    @Test
    void fromString_string_returnsAsIs() {
        assertThat(ValueCoercion.fromString("hello", String.class)).isEqualTo("hello");
    }

    @Test
    void fromString_uuid_parses() {
        UUID id = UUID.randomUUID();
        assertThat(ValueCoercion.fromString(id.toString(), UUID.class)).isEqualTo(id);
    }

    @Test
    void fromString_primitiveAndBoxedNumbers_parse() {
        assertThat(ValueCoercion.fromString("42", long.class)).isEqualTo(42L);
        assertThat(ValueCoercion.fromString("42", Long.class)).isEqualTo(42L);
        assertThat(ValueCoercion.fromString("7", int.class)).isEqualTo(7);
        assertThat(ValueCoercion.fromString("3.5", double.class)).isEqualTo(3.5);
    }

    @Test
    void fromString_boolean_parses() {
        assertThat(ValueCoercion.fromString("true", boolean.class)).isEqualTo(true);
        assertThat(ValueCoercion.fromString("false", Boolean.class)).isEqualTo(false);
    }

    @Test
    void fromString_enum_parsesByName() {
        assertThat(ValueCoercion.fromString("BROADCASTER", ChatCommandEvent.SenderRole.class))
            .isEqualTo(ChatCommandEvent.SenderRole.BROADCASTER);
    }

    @Test
    void fromString_instant_parsesIso8601() {
        Instant i = Instant.parse("2026-08-01T00:00:00Z");
        assertThat(ValueCoercion.fromString("2026-08-01T00:00:00Z", Instant.class)).isEqualTo(i);
    }

    @Test
    void fromString_unsupportedType_throws() {
        assertThatThrownBy(() -> ValueCoercion.fromString("x", Object.class))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toString_roundTripsUuid() {
        UUID id = UUID.randomUUID();
        assertThat(ValueCoercion.fromString(ValueCoercion.toString(id), UUID.class)).isEqualTo(id);
    }

    @Test
    void toString_nullReturnsEmptyString() {
        assertThat(ValueCoercion.toString(null)).isEqualTo("");
    }
}
