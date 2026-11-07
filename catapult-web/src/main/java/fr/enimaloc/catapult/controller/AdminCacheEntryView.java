package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.CacheEntryDetailDto;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A cache entry as its detail dialog shows it: the record behind it as fields, or, for a cache
 * holding lists (a member's chat commands), a table over every key of its records.
 */
public record AdminCacheEntryView(Long expiresInSeconds, List<Field> fields, List<String> columns, List<List<String>> rows) {
    private static final String NONE = "—";

    public record Field(String name, String value) {}

    public boolean empty() {
        return fields.isEmpty() && rows.isEmpty();
    }

    /** Null when catapult-api didn't answer. */
    public static AdminCacheEntryView of(CacheEntryDetailDto dto) {
        if (dto == null) return null;
        Object detail = dto.detail();
        if (detail instanceof Map<?, ?> map) {
            List<Field> fields = map.entrySet().stream()
                    .map(e -> new Field(String.valueOf(e.getKey()), text(e.getValue()))).toList();
            return new AdminCacheEntryView(dto.expiresInSeconds(), fields, List.of(), List.of());
        }
        if (detail instanceof List<?> list) {
            List<String> columns = list.stream().filter(Map.class::isInstance)
                    .flatMap(item -> ((Map<?, ?>) item).keySet().stream()).map(String::valueOf).distinct().toList();
            List<String> shown = columns.isEmpty() ? List.of("value") : columns;
            List<List<String>> rows = list.stream()
                    .map(item -> item instanceof Map<?, ?> m
                            ? shown.stream().map(c -> text(m.get(c))).toList()
                            : List.of(text(item)))
                    .toList();
            return new AdminCacheEntryView(dto.expiresInSeconds(), List.of(), shown, rows);
        }
        List<Field> fields = detail == null ? List.of() : List.of(new Field("value", text(detail)));
        return new AdminCacheEntryView(dto.expiresInSeconds(), fields, List.of(), List.of());
    }

    private static String text(Object value) {
        return Objects.toString(value, NONE);
    }
}
