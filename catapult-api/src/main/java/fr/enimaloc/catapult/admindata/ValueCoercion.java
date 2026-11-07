package fr.enimaloc.catapult.admindata;

import java.time.Instant;
import java.util.UUID;

/** Generic scalar String <-> typed value conversion, shared by IdCodec and the entity form binder. */
public final class ValueCoercion {

    private ValueCoercion() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object fromString(String raw, Class<?> targetType) {
        if (targetType == String.class) {
            return raw;
        }
        if (targetType == UUID.class) {
            return UUID.fromString(raw);
        }
        if (targetType == long.class || targetType == Long.class) {
            return Long.parseLong(raw);
        }
        if (targetType == int.class || targetType == Integer.class) {
            return Integer.parseInt(raw);
        }
        if (targetType == double.class || targetType == Double.class) {
            return Double.parseDouble(raw);
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return Boolean.parseBoolean(raw);
        }
        if (targetType == Instant.class) {
            return Instant.parse(raw);
        }
        if (targetType.isEnum()) {
            return Enum.valueOf((Class<Enum>) targetType, raw);
        }
        throw new IllegalArgumentException("Unsupported scalar type for value coercion: " + targetType);
    }

    public static String toString(Object value) {
        return value == null ? "" : value.toString();
    }
}
