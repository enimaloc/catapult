package fr.enimaloc.catapult.service.debug.wire;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class ProtobufWireDecoderTest {

    @Test
    void decodesVarintAndString() {
        byte[] bytes = {0x08, (byte) 0x96, 0x01, 0x12, 3, 'a', 'b', 'c'};

        Optional<ObjectNode> node = ProtobufWireDecoder.decode(bytes);

        assertThat(node).isPresent();
        assertThat(node.get().toString()).isEqualTo("{\"1\":150,\"2\":\"abc\"}");
    }

    @Test
    void decodesNestedMessage() {
        // field 1 = message { field 1 = 7, field 2 = 9 } (not printable text)
        byte[] bytes = {0x0A, 4, 0x08, 0x07, 0x10, 0x09};

        assertThat(ProtobufWireDecoder.decode(bytes).orElseThrow().toString())
                .isEqualTo("{\"1\":{\"1\":7,\"2\":9}}");
    }

    @Test
    void repeatedFieldBecomesArray() {
        byte[] bytes = {0x08, 1, 0x08, 2, 0x08, 3};

        assertThat(ProtobufWireDecoder.decode(bytes).orElseThrow().toString()).isEqualTo("{\"1\":[1,2,3]}");
    }

    @Test
    void printableTextStaysAString() {
        byte[] bytes = {0x12, 8, 'F', 'o', 'o', '#', '1', '2', '3', '4'};

        assertThat(ProtobufWireDecoder.decode(bytes).orElseThrow().toString()).isEqualTo("{\"2\":\"Foo#1234\"}");
    }

    @Test
    void undecodableBytesBecomeHexObject() {
        // field 1 = 2 bytes 0xFF 0xFE: neither text nor a message
        byte[] bytes = {0x0A, 2, (byte) 0xFF, (byte) 0xFE};

        assertThat(ProtobufWireDecoder.decode(bytes).orElseThrow().toString())
                .isEqualTo("{\"1\":{\"bytes\":\"fffe\"}}");
    }

    @Test
    void decodesFixedWidthFields() {
        byte[] bytes = {0x0D, 5, 0, 0, 0, 0x11, 6, 0, 0, 0, 0, 0, 0, 0};

        assertThat(ProtobufWireDecoder.decode(bytes).orElseThrow().toString()).isEqualTo("{\"1\":5,\"2\":6}");
    }

    @Test
    void invalidMessageReturnsEmpty() {
        assertThat(ProtobufWireDecoder.decode(new byte[] {(byte) 0xFF})).isEmpty();
    }

    @Test
    void emptyOrNullInputReturnsEmpty() {
        assertThat(ProtobufWireDecoder.decode(new byte[0])).isEmpty();
        assertThat(ProtobufWireDecoder.decode(null)).isEmpty();
    }
}
