package fr.enimaloc.catapult.admindata;

import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.SingularAttribute;

import java.lang.reflect.Field;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;

/**
 * Encodes an entity's primary key (simple, or composite via @IdClass) into a single URL path
 * segment, and decodes it back. Composite components are ordered alphabetically by attribute
 * name so encoding is deterministic without depending on declaration order.
 */
public final class IdCodec {

    private IdCodec() {}

    public static String encode(Object id, EntityType<?> entityType) {
        if (entityType.hasSingleIdAttribute()) {
            return encodeComponent(ValueCoercion.toString(id));
        }
        List<SingularAttribute<?, ?>> attrs = sortedIdClassAttributes(entityType);
        Class<?> idClass = id.getClass();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < attrs.size(); i++) {
            if (i > 0) sb.append('~');
            try {
                Field f = idClass.getDeclaredField(attrs.get(i).getName());
                f.setAccessible(true);
                sb.append(encodeComponent(ValueCoercion.toString(f.get(id))));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot read id field " + attrs.get(i).getName()
                    + " from " + idClass, e);
            }
        }
        return sb.toString();
    }

    public static Object decode(String encoded, EntityType<?> entityType) {
        String[] parts = encoded.split("~", -1);
        if (entityType.hasSingleIdAttribute()) {
            Class<?> idType = entityType.getIdType().getJavaType();
            return ValueCoercion.fromString(decodeComponent(parts[0]), idType);
        }
        Class<?> idClass = entityType.getIdType().getJavaType();
        List<SingularAttribute<?, ?>> attrs = sortedIdClassAttributes(entityType);
        try {
            Object pk = idClass.getDeclaredConstructor().newInstance();
            for (int i = 0; i < attrs.size(); i++) {
                Field f = idClass.getDeclaredField(attrs.get(i).getName());
                f.setAccessible(true);
                f.set(pk, ValueCoercion.fromString(decodeComponent(parts[i]), f.getType()));
            }
            return pk;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot decode id for " + entityType.getJavaType(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<SingularAttribute<?, ?>> sortedIdClassAttributes(EntityType<?> entityType) {
        return (List<SingularAttribute<?, ?>>) (List<?>) entityType.getIdClassAttributes().stream()
            .sorted(Comparator.comparing(Attribute::getName))
            .toList();
    }

    private static String encodeComponent(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    private static String decodeComponent(String encoded) {
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }
}
