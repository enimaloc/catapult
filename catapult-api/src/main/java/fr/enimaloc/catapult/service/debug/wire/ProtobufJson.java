package fr.enimaloc.catapult.service.debug.wire;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Prints a parsed protobuf message as JSON from its descriptor: fields keyed by their proto name, repeated fields as
 * arrays, 64-bit integers as strings (as protobuf's JSON mapping does), enums by name, bytes as text when printable
 * else as {@code {"bytes": "<hex>"}}. Unknown fields are left out.
 */
public final class ProtobufJson {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private ProtobufJson() {
    }

    public static ObjectNode of(Message message) {
        ObjectNode node = NODES.objectNode();
        for (Map.Entry<FieldDescriptor, Object> field : message.getAllFields().entrySet()) {
            FieldDescriptor descriptor = field.getKey();
            if (descriptor.isRepeated()) {
                ArrayNode array = NODES.arrayNode();
                for (Object value : (List<?>) field.getValue()) {
                    array.add(value(descriptor, value));
                }
                node.set(descriptor.getName(), array);
            } else {
                node.set(descriptor.getName(), value(descriptor, field.getValue()));
            }
        }
        return node;
    }

    private static JsonNode value(FieldDescriptor descriptor, Object value) {
        return switch (descriptor.getType()) {
            case MESSAGE, GROUP -> of((Message) value);
            case ENUM -> NODES.stringNode(((EnumValueDescriptor) value).getName());
            case BYTES -> bytes((ByteString) value);
            case INT64, SINT64, SFIXED64 -> NODES.stringNode(Long.toString((Long) value));
            case UINT64, FIXED64 -> NODES.stringNode(Long.toUnsignedString((Long) value));
            case UINT32, FIXED32 -> NODES.numberNode(Integer.toUnsignedLong((Integer) value));
            case INT32, SINT32, SFIXED32 -> NODES.numberNode((Integer) value);
            case BOOL -> NODES.booleanNode((Boolean) value);
            case FLOAT -> NODES.numberNode((Float) value);
            case DOUBLE -> NODES.numberNode((Double) value);
            case STRING -> NODES.stringNode((String) value);
        };
    }

    private static JsonNode bytes(ByteString value) {
        byte[] raw = value.toByteArray();
        String text = raw.length == 0 ? "" : ProtobufWireDecoder.printableText(raw);
        if (text != null) {
            return NODES.stringNode(text);
        }
        ObjectNode hex = NODES.objectNode();
        hex.put("bytes", RawBytes.hex(raw));
        return hex;
    }
}
