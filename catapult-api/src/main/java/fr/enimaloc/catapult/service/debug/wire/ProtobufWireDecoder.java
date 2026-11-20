package fr.enimaloc.catapult.service.debug.wire;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Schema-less protobuf decoder: turns the bytes of a message into a JSON object keyed by field number, so a frame
 * can be shown and searched without its {@code .proto}. Repeated fields become arrays.
 */
public final class ProtobufWireDecoder {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final int MAX_DEPTH = 8;

    private ProtobufWireDecoder() {
    }

    /** The decoded message, or empty when {@code bytes} is not a valid, non-empty protobuf message. */
    public static Optional<ObjectNode> decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return Optional.empty();
        }
        return parse(bytes, 0);
    }

    private static Optional<ObjectNode> parse(byte[] bytes, int depth) {
        try {
            CodedInputStream in = CodedInputStream.newInstance(bytes);
            ObjectNode node = NODES.objectNode();
            while (!in.isAtEnd()) {
                int tag = in.readTag();
                int field = WireFormat.getTagFieldNumber(tag);
                if (field == 0) {
                    return Optional.empty();
                }
                add(node, Integer.toString(field), readValue(in, WireFormat.getTagWireType(tag), depth));
            }
            return node.isEmpty() ? Optional.empty() : Optional.of(node);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static JsonNode readValue(CodedInputStream in, int wireType, int depth) throws IOException {
        switch (wireType) {
            case WireFormat.WIRETYPE_VARINT:
                long varint = in.readRawVarint64();
                return varint >= 0 ? NODES.numberNode(varint)
                        : NODES.numberNode(new BigInteger(Long.toUnsignedString(varint)));
            case WireFormat.WIRETYPE_FIXED32:
                return NODES.numberNode(in.readFixed32() & 0xFFFFFFFFL);
            case WireFormat.WIRETYPE_FIXED64:
                long fixed = in.readFixed64();
                return fixed >= 0 ? NODES.numberNode(fixed)
                        : NODES.numberNode(new BigInteger(Long.toUnsignedString(fixed)));
            case WireFormat.WIRETYPE_LENGTH_DELIMITED:
                return lengthDelimited(in.readByteArray(), depth);
            default:
                throw new IOException("Unsupported wire type " + wireType);
        }
    }

    /** Printable text first (short strings often parse as messages), then a nested message, else hex. */
    private static JsonNode lengthDelimited(byte[] payload, int depth) {
        String text = printableText(payload);
        if (text != null) {
            return NODES.stringNode(text);
        }
        if (depth < MAX_DEPTH) {
            Optional<ObjectNode> nested = parse(payload, depth + 1);
            if (nested.isPresent()) {
                return nested.get();
            }
        }
        ObjectNode hex = NODES.objectNode();
        hex.put("bytes", RawBytes.hex(payload));
        return hex;
    }

    private static String printableText(byte[] payload) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
        boolean printable = text.codePoints().allMatch(c -> !Character.isISOControl(c) || c == '\t' || c == '\n'
                || c == '\r');
        return printable ? text : null;
    }

    private static void add(ObjectNode node, String key, JsonNode value) {
        JsonNode existing = node.get(key);
        if (existing == null) {
            node.set(key, value);
        } else if (existing.isArray()) {
            ((ArrayNode) existing).add(value);
        } else {
            ArrayNode array = NODES.arrayNode();
            array.add(existing);
            array.add(value);
            node.set(key, array);
        }
    }
}
