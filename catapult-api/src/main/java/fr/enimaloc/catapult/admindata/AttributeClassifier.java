package fr.enimaloc.catapult.admindata;

import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.SingularAttribute;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class AttributeClassifier {

    private AttributeClassifier() {}

    public static AttributeKind classify(Attribute<?, ?> attribute) {
        return switch (attribute.getPersistentAttributeType()) {
            case MANY_TO_ONE, ONE_TO_ONE -> AttributeKind.SINGULAR_RELATION;
            case ONE_TO_MANY, MANY_TO_MANY -> AttributeKind.COLLECTION_RELATION;
            case BASIC -> isConverted(attribute) ? AttributeKind.CONVERTED : AttributeKind.BASIC;
            case EMBEDDED -> AttributeKind.CONVERTED;
            default -> AttributeKind.CONVERTED;
        };
    }

    private static boolean isConverted(Attribute<?, ?> attribute) {
        if (!(attribute instanceof SingularAttribute<?, ?> singular)) {
            return false;
        }
        Class<?> javaType = singular.getJavaType();
        // A BASIC-typed attribute whose Java type isn't a plain scalar (String/number/boolean/
        // enum/Instant/UUID) is backed by an AttributeConverter or a native JSON column mapping
        // (e.g. Map<String,String>, List<String>, Set<String>) — treat it as CONVERTED.
        return !(javaType == String.class
            || javaType.isEnum()
            || javaType == boolean.class || javaType == Boolean.class
            || javaType == int.class || javaType == Integer.class
            || javaType == long.class || javaType == Long.class
            || javaType == double.class || javaType == Double.class
            || javaType == java.time.Instant.class
            || javaType == java.util.UUID.class);
    }

    public static String label(Object entity, EntityType<?> entityType) {
        if (entity == null) {
            return "";
        }
        if (overridesToString(entity.getClass())) {
            return entity.toString();
        }
        Object id = readId(entity, entityType);
        String firstStringAttr = firstBasicStringValue(entity, entityType);
        return firstStringAttr != null
            ? id + " — " + firstStringAttr
            : String.valueOf(id);
    }

    private static boolean overridesToString(Class<?> type) {
        try {
            Method m = type.getMethod("toString");
            return m.getDeclaringClass() != Object.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static Object readId(Object entity, EntityType<?> entityType) {
        if (!entityType.hasSingleIdAttribute()) {
            return "?";
        }
        return readField(entity, entityType.getId(entityType.getIdType().getJavaType()).getName());
    }

    private static String firstBasicStringValue(Object entity, EntityType<?> entityType) {
        for (Attribute<?, ?> attribute : entityType.getAttributes()) {
            if (classify(attribute) == AttributeKind.BASIC
                    && attribute instanceof SingularAttribute<?, ?> singular
                    && singular.getJavaType() == String.class) {
                Object value = readField(entity, attribute.getName());
                if (value != null) {
                    return (String) value;
                }
            }
        }
        return null;
    }

    private static Object readField(Object entity, String fieldName) {
        try {
            Field f = findField(entity.getClass(), fieldName);
            f.setAccessible(true);
            return f.get(entity);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read field " + fieldName + " on " + entity.getClass(), e);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name + " on " + type);
    }
}
