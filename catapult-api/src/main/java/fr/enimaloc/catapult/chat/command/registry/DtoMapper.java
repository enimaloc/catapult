package fr.enimaloc.catapult.chat.command.registry;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a strongly-typed result record into the {@code Map<String, Object>} a service
 * function must return at the sandbox boundary (a host {@link Map} is what {@link
 * fr.enimaloc.catapult.chat.command.js.SandboxExecutor} exposes to JS bracket/member access,
 * see its {@code allowMapAccess(true)} host access config) — while keeping the record itself as
 * the single source of truth for both the values a function returns and the {@code
 * ServiceFunction#returnKeys()} the Blocks editor's property-get dropdown reads, so the two can
 * never drift out of sync the way a hand-maintained {@code returnKeys()} list could.
 */
public final class DtoMapper {

    private DtoMapper() {
    }

    public static Map<String, Object> toMap(Record dto) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (RecordComponent component : dto.getClass().getRecordComponents()) {
            try {
                map.put(component.getName(), component.getAccessor().invoke(dto));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to read record component " + component.getName(), e);
            }
        }
        return map;
    }

    public static List<String> keys(Class<? extends Record> dtoClass) {
        RecordComponent[] components = dtoClass.getRecordComponents();
        List<String> keys = new ArrayList<>(components.length);
        for (RecordComponent component : components) {
            keys.add(component.getName());
        }
        return List.copyOf(keys);
    }
}
