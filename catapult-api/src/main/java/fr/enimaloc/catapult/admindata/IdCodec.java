package fr.enimaloc.catapult.admindata;

import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EmbeddableType;
import jakarta.persistence.metamodel.EntityType;

import java.lang.reflect.Field;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Encodes an entity's primary key (simple, or composite via @IdClass or @EmbeddedId) into a single URL path
 * segment, and decodes it back. Composite components are ordered alphabetically by attribute
 * name so encoding is deterministic without depending on declaration order.
 */
public final class IdCodec {

    private IdCodec() {}

    public static String encode(Object id, EntityType<?> entityType) {
        List<String> components = sortedComponentNames(entityType);
        if (components.isEmpty()) {
            return encodeComponent(ValueCoercion.toString(id));
        }
        Class<?> idClass = id.getClass();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < components.size(); i++) {
            if (i > 0) sb.append('~');
            try {
                Field f = idClass.getDeclaredField(components.get(i));
                f.setAccessible(true);
                sb.append(encodeComponent(ValueCoercion.toString(f.get(id))));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot read id field " + components.get(i)
                    + " from " + idClass, e);
            }
        }
        return sb.toString();
    }

    public static Object decode(String encoded, EntityType<?> entityType) {
        String[] parts = encoded.split("~", -1);
        Class<?> idClass = entityType.getIdType().getJavaType();
        List<String> components = sortedComponentNames(entityType);
        if (components.isEmpty()) {
            return ValueCoercion.fromString(decodeComponent(parts[0]), idClass);
        }
        try {
            Object pk = idClass.getDeclaredConstructor().newInstance();
            for (int i = 0; i < components.size(); i++) {
                Field f = idClass.getDeclaredField(components.get(i));
                f.setAccessible(true);
                f.set(pk, ValueCoercion.fromString(decodeComponent(parts[i]), f.getType()));
            }
            return pk;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot decode id for " + entityType.getJavaType(), e);
        }
    }

    /** Composite id component names in alphabetical order; empty for a simple id. */
    private static List<String> sortedComponentNames(EntityType<?> entityType) {
        if (!entityType.hasSingleIdAttribute()) {
            return entityType.getIdClassAttributes().stream().map(Attribute::getName).sorted().toList();
        }
        if (entityType.getIdType() instanceof EmbeddableType<?> embeddable) {
            return embeddable.getAttributes().stream().map(Attribute::getName).sorted().toList();
        }
        return List.of();
    }

    private static String encodeComponent(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    private static String decodeComponent(String encoded) {
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }
}
