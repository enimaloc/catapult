package fr.enimaloc.catapult.common.dto;


import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every record in this package (and its sub-packages) crosses the api/web boundary as JSON. Builds one fully populated
 * instance of each through its canonical constructor and checks that Jackson 3 (what both
 * modules use) writes it and reads back an equal value — catching records that can't be
 * deserialized, or whose JSON shape silently loses a field.
 */
class DtoJsonContractTest {

    private static final String PACKAGE = DtoJsonContractTest.class.getPackageName();

    private final ObjectMapper json = JsonMapper.builder().build();

    static Stream<Class<?>> records() throws IOException, URISyntaxException {
        URL root = AppConfigResponse.class.getResource("AppConfigResponse.class");
        Path dir = Path.of(root.toURI()).getParent();
        List<Class<?>> records = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String relative = dir.relativize(file).toString();
                if (!relative.endsWith(".class")) continue;
                String simpleName = relative.substring(0, relative.length() - ".class".length())
                    .replace(file.getFileSystem().getSeparator(), ".");
                Class<?> type = load(PACKAGE + "." + simpleName);
                if (type.isRecord()) records.add(type);
            }
        }
        records.sort((a, b) -> a.getName().compareTo(b.getName()));
        assertThat(records).as("records found in %s", PACKAGE).hasSizeGreaterThan(100);
        return records.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("records")
    void roundTripsThroughJson(Class<?> type) {
        Object instance = sample(type);

        String written = json.writeValueAsString(instance);
        Object read = json.readValue(written, type);

        assertThat(read).usingRecursiveComparison().isEqualTo(instance);
    }

    // --- sample values ------------------------------------------------------------------------

    private static Object sample(Type type) {
        if (type instanceof ParameterizedType parameterized) {
            Class<?> raw = (Class<?>) parameterized.getRawType();
            Type[] args = parameterized.getActualTypeArguments();
            if (List.class.isAssignableFrom(raw)) return List.of(sample(args[0]));
            if (Set.class.isAssignableFrom(raw)) return Set.of(sample(args[0]));
            if (Map.class.isAssignableFrom(raw)) return Map.of(sample(args[0]), sample(args[1]));
            return sample(raw);
        }
        Class<?> cls = (Class<?>) type;
        if (cls == String.class) return "value";
        if (cls == int.class || cls == Integer.class) return 7;
        if (cls == long.class || cls == Long.class) return 7L;
        if (cls == double.class || cls == Double.class) return 0.5;
        if (cls == float.class || cls == Float.class) return 0.5f;
        if (cls == boolean.class || cls == Boolean.class) return true;
        if (cls == Instant.class) return Instant.parse("2026-01-02T03:04:05Z");
        if (cls == LocalDate.class) return LocalDate.parse("2026-01-02");
        if (cls == UUID.class) return UUID.fromString("00000000-0000-0000-0000-000000000001");
        if (cls == Object.class) return "value";
        if (cls.isEnum()) return cls.getEnumConstants()[0];
        if (cls.isArray()) {
            Object array = Array.newInstance(cls.getComponentType(), 1);
            Array.set(array, 0, sample(cls.getComponentType()));
            return array;
        }
        if (List.class.isAssignableFrom(cls)) return Collections.emptyList();
        if (Map.class.isAssignableFrom(cls)) return Collections.emptyMap();
        if (cls.isRecord()) return sample(cls);
        if (cls.isSealed()) return sample(cls.getPermittedSubclasses()[0]);
        throw new IllegalArgumentException("No sample value for " + cls.getName() + " — add one to DtoJsonContractTest");
    }

    private static Object sample(Class<?> record) {
        if (!record.isRecord()) return sample((Type) record);
        RecordComponent[] components = record.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            values[i] = sample(components[i].getGenericType());
        }
        try {
            var constructor = record.getDeclaredConstructor(types);
            constructor.setAccessible(true);
            return constructor.newInstance(values);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot build a " + record.getName(), e);
        }
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
