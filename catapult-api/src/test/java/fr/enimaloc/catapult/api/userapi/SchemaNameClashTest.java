package fr.enimaloc.catapult.api.userapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.annotations.media.Schema;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RestController;

/**
 * springdoc names a schema after the record's simple name: two documented records sharing one (BlizzardStats.Character
 * and BungieStats.Character) silently become one schema, the first registered wins. Every record reachable from a
 * public API answer must therefore carry a name of its own, else an {@code @Schema(name = ...)}.
 */
class SchemaNameClashTest {

    @Test
    void everyDocumentedRecordHasItsOwnSchemaName() throws Exception {
        Set<Class<?>> records = reachableRecords();
        Map<String, Set<String>> byName = new TreeMap<>();
        for (Class<?> type : records) {
            byName.computeIfAbsent(schemaName(type), n -> new TreeSet<>()).add(type.getName());
        }
        byName.values().removeIf(types -> types.size() < 2);

        assertThat(byName).as("records documented under one schema name").isEmpty();
    }

    private static Set<Class<?>> reachableRecords() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Deque<Type> pending = new ArrayDeque<>();
        for (var candidate : scanner.findCandidateComponents("fr.enimaloc.catapult.api")) {
            for (Method method : Class.forName(candidate.getBeanClassName()).getDeclaredMethods()) {
                pending.add(method.getGenericReturnType());
            }
        }
        pending.add(GameInfoResponse.class);
        pending.add(GameInfoV3Response.class);

        Set<Class<?>> records = new HashSet<>();
        Set<Type> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            Type type = pending.pop();
            if (!seen.add(type)) {
                continue;
            }
            switch (type) {
                case ParameterizedType parameterized -> {
                    pending.add(parameterized.getRawType());
                    pending.addAll(java.util.List.of(parameterized.getActualTypeArguments()));
                }
                case GenericArrayType array -> pending.add(array.getGenericComponentType());
                case Class<?> raw when raw.isArray() -> pending.add(raw.getComponentType());
                case Class<?> raw when raw.isRecord() && raw.getName().startsWith("fr.enimaloc.catapult") -> {
                    records.add(raw);
                    for (RecordComponent component : raw.getRecordComponents()) {
                        pending.add(component.getGenericType());
                    }
                }
                default -> { }
            }
        }
        return records;
    }

    private static String schemaName(Class<?> type) {
        Schema schema = type.getAnnotation(Schema.class);
        return schema != null && !schema.name().isBlank() ? schema.name() : type.getSimpleName();
    }
}
