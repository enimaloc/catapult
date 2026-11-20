package fr.enimaloc.catapult.service.debug.wire;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RawBytesTest {

    @Test
    void contains_findsUtf8TextCaseInsensitively() {
        byte[] raw = "xxfoo#1234yy".getBytes(StandardCharsets.UTF_8);
        assertThat(RawBytes.contains(raw, "Foo#1234")).isTrue();
    }

    @Test
    void contains_findsANumberAsVarint() {
        assertThat(RawBytes.contains(new byte[]{0x08, (byte) 0xAC, 0x02}, "300")).isTrue();
    }

    @Test
    void contains_findsANumberAsFixed64LittleEndian() {
        byte[] raw = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(1L).array();
        assertThat(RawBytes.contains(raw, "1")).isTrue();
    }

    @Test
    void contains_returnsFalseForNullOrEmpty() {
        assertThat(RawBytes.contains(null, "abc")).isFalse();
        assertThat(RawBytes.contains(new byte[0], "abc")).isFalse();
        assertThat(RawBytes.contains(new byte[]{1, 2}, null)).isFalse();
        assertThat(RawBytes.contains(new byte[]{1, 2}, "")).isFalse();
    }

    @Test
    void contains_returnsFalseWhenAbsent() {
        assertThat(RawBytes.contains("hello".getBytes(StandardCharsets.UTF_8), "Foo#1234")).isFalse();
    }

    @Test
    void hex_isLowercaseWithoutSeparators() {
        assertThat(RawBytes.hex(new byte[]{0x0A, (byte) 0xFF})).isEqualTo("0aff");
        assertThat(RawBytes.hex(null)).isNull();
    }
}
