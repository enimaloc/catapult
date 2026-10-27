package fr.enimaloc.catapult.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How the admin data tab shows a repository's rows and a row's dialog, out of catapult-api's
 * generic row maps: a relation's id comes with a "<field>_label" companion (display only), and a
 * row's detail adds its collection relations as lists of {id, label} — read-only.
 */
public final class AdminDataViews {
    private static final String LABEL_SUFFIX = "_label";
    private static final String NONE = "—";

    private AdminDataViews() {
    }

    public record Row(String id, List<String> cells) {}

    public record Table(List<String> columns, List<Row> rows) {}

    public record Field(String name, String value, String label) {}

    public record Link(String id, String label) {}

    public record Form(String repo, String id, List<Field> fields, Map<String, List<Link>> collections) {
        public boolean creating() {
            return id == null;
        }
    }

    /** The rows' columns (the first row's keys but the labels) and cells (a relation followed by its label). */
    public static Table table(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return new Table(List.of(), List.of());
        List<String> columns = rows.getFirst().keySet().stream().filter(k -> !k.endsWith(LABEL_SUFFIX)).toList();
        return new Table(columns, rows.stream()
                .map(row -> new Row(String.valueOf(row.get("id")), columns.stream().map(c -> cell(row, c)).toList()))
                .toList());
    }

    /** {@code row}'s dialog: editing it ({@code id} given) or, laid out on its keys, creating a new one. */
    public static Form form(String repo, String id, Map<String, Object> row) {
        List<Field> fields = new ArrayList<>();
        Map<String, List<Link>> collections = new LinkedHashMap<>();
        row.forEach((name, value) -> {
            if (name.endsWith(LABEL_SUFFIX)) return;
            if (value instanceof List<?> items) {
                if (id != null) collections.put(name, items.stream().map(AdminDataViews::link).toList());
                return;
            }
            if (id != null && name.equals("id")) return; // an existing row's id is fixed
            Object label = id == null ? null : row.get(name + LABEL_SUFFIX);
            fields.add(new Field(name, id == null || value == null ? "" : String.valueOf(value),
                    label == null ? null : String.valueOf(label)));
        });
        return new Form(repo, id, fields, collections);
    }

    private static String cell(Map<String, Object> row, String column) {
        String value = Objects.toString(row.get(column), NONE);
        Object label = row.get(column + LABEL_SUFFIX);
        return label == null ? value : value + " · " + label;
    }

    private static Link link(Object item) {
        if (item instanceof Map<?, ?> map) {
            return new Link(String.valueOf(map.get("id")), String.valueOf(map.get("label")));
        }
        return new Link(String.valueOf(item), String.valueOf(item));
    }
}
