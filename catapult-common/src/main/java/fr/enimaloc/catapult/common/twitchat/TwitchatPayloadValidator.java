package fr.enimaloc.catapult.common.twitchat;

import fr.enimaloc.catapult.common.dto.twitchat.TwitchatEventActionDto;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatValidationError;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Checks payloads against the catalog: a known action, a JSON object, and in FORM mode the
 * schema of its fields (required, type, choices, rows). RAW mode is the streamer's override and
 * only needs to be a JSON object. A select fed live from Twitchat ({@code source}) accepts any
 * value, the list being unknown here; a value containing a {{placeholder}} passes as a number.
 */
public final class TwitchatPayloadValidator {

    public static final String FORM = "FORM";
    public static final String RAW = "RAW";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT = new TypeReference<>() {
    };

    private final TwitchatCatalog catalog;

    public TwitchatPayloadValidator(TwitchatCatalog catalog) {
        this.catalog = catalog;
    }

    public List<TwitchatValidationError> validate(List<TwitchatEventActionDto> payloads) {
        List<TwitchatValidationError> errors = new ArrayList<>();
        for (int i = 0; i < payloads.size(); i++) {
            TwitchatEventActionDto payload = payloads.get(i);
            Optional<TwitchatCatalog.Action> action = payload.action() == null
                    ? Optional.empty() : catalog.action(payload.action());
            if (action.isEmpty()) {
                errors.add(new TwitchatValidationError(i, "action", "Unknown Twitchat action: " + payload.action()));
                continue;
            }
            if (action.get().dataOnly()) {
                errors.add(new TwitchatValidationError(i, "action", "Only returns data, does nothing: " + payload.action()));
                continue;
            }
            if (!FORM.equals(payload.mode()) && !RAW.equals(payload.mode())) {
                errors.add(new TwitchatValidationError(i, "mode", "Expected FORM or RAW"));
                continue;
            }
            Map<String, Object> data;
            try {
                data = parse(payload.dataJson());
            } catch (IllegalArgumentException e) {
                errors.add(new TwitchatValidationError(i, "dataJson", e.getMessage()));
                continue;
            }
            if (FORM.equals(payload.mode())) {
                if (action.get().rawOnly()) {
                    errors.add(new TwitchatValidationError(i, "mode", "This action has no form"));
                } else {
                    validateFields(i, "", action.get().fields(), data, errors);
                }
            }
        }
        return errors;
    }

    /** {@code dataJson} as an ordered map; IllegalArgumentException unless it's a JSON object. */
    public Map<String, Object> parse(String dataJson) {
        if (dataJson == null || !dataJson.trim().startsWith("{")) {
            throw new IllegalArgumentException("Not a JSON object");
        }
        try {
            return JSON.readValue(dataJson, OBJECT);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getOriginalMessage());
        }
    }

    private void validateFields(int index, String prefix, List<TwitchatCatalog.Field> fields, Map<String, Object> data,
                                List<TwitchatValidationError> errors) {
        for (TwitchatCatalog.Field field : fields) {
            if (field.path() == null) {
                continue;
            }
            String name = prefix + field.path();
            Object value = valueAt(data, field.path());
            if (value == null || value instanceof String text && text.isBlank()) {
                if (field.required()) {
                    errors.add(new TwitchatValidationError(index, name, "Required"));
                }
                continue;
            }
            String problem = switch (field.type()) {
                case "text", "textarea" -> value instanceof String ? null : "Expected text";
                case "number" -> value instanceof Number || value instanceof String text && text.contains("{{")
                        ? null : "Expected a number";
                case "switch" -> value instanceof Boolean ? null : "Expected true or false";
                case "select" -> field.options() == null
                        || field.options().stream().anyMatch(option -> option.value().equals(String.valueOf(value)))
                        ? null : "Not one of the choices";
                case "choice" -> field.options() == null
                        || field.options().stream().anyMatch(option -> option.value().equals(JSON.writeValueAsString(value)))
                        ? null : "Not one of the choices";
                case "repeater" -> value instanceof List<?> ? null : "Expected a list";
                case "list" -> value instanceof List<?> items && items.stream().allMatch(String.class::isInstance)
                        ? null : "Expected a list of texts";
                default -> null;
            };
            if (problem != null) {
                errors.add(new TwitchatValidationError(index, name, problem));
            } else if ("repeater".equals(field.type())) {
                List<?> rows = (List<?>) value;
                for (int r = 0; r < rows.size(); r++) {
                    String rowName = name + "[" + r + "]";
                    if (rows.get(r) instanceof Map<?, ?> row) {
                        @SuppressWarnings("unchecked") Map<String, Object> rowData = (Map<String, Object>) row;
                        validateFields(index, rowName + ".", field.fields(), rowData, errors);
                    } else {
                        errors.add(new TwitchatValidationError(index, rowName, "Expected an object"));
                    }
                }
            }
        }
    }

    /**
     * The keys of {@code data} no field of the form holds, each reported as "Not in the form":
     * the form would drop them. A field covers its path, a json one its whole subtree, and a
     * repeater's rows are checked against its row fields. Mistyped values are validate()'s job.
     */
    public List<TwitchatValidationError> outsideForm(int index, List<TwitchatCatalog.Field> fields, Map<String, Object> data) {
        List<TwitchatValidationError> errors = new ArrayList<>();
        outsideForm(index, "", coverage(fields), data, errors);
        return errors;
    }

    @SuppressWarnings("unchecked")
    private static void outsideForm(int index, String prefix, Map<String, Object> coverage, Map<String, Object> data,
                                    List<TwitchatValidationError> errors) {
        data.forEach((key, value) -> {
            String name = prefix + key;
            Object covered = coverage.get(key);
            if (covered instanceof TwitchatCatalog.Field field) {
                if ("repeater".equals(field.type()) && value instanceof List<?> rows) {
                    for (int r = 0; r < rows.size(); r++) {
                        if (rows.get(r) instanceof Map<?, ?> row) {
                            outsideForm(index, name + "[" + r + "].", coverage(field.fields()), (Map<String, Object>) row, errors);
                        }
                    }
                }
            } else if (covered instanceof Map<?, ?> children && value instanceof Map<?, ?> object) {
                outsideForm(index, name + ".", (Map<String, Object>) children, (Map<String, Object>) object, errors);
            } else if (covered == null || value != null) {
                errors.add(new TwitchatValidationError(index, name, "Not in the form"));
            }
        });
    }

    /** The fields by path step: a Field where one ends (holding all below it), else the next steps. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> coverage(List<TwitchatCatalog.Field> fields) {
        Map<String, Object> root = new LinkedHashMap<>();
        for (TwitchatCatalog.Field field : fields == null ? List.<TwitchatCatalog.Field>of() : fields) {
            if (field.path() == null) {
                continue;
            }
            String[] steps = field.path().split("\\.");
            Map<String, Object> node = root;
            for (int i = 0; i < steps.length - 1 && node != null; i++) {
                Object child = node.computeIfAbsent(steps[i], ignored -> new LinkedHashMap<String, Object>());
                node = child instanceof Map<?, ?> map ? (Map<String, Object>) map : null; // under a field: covered
            }
            if (node != null) {
                node.put(steps[steps.length - 1], field);
            }
        }
        return root;
    }

    /** The value at a dotted path ("user.name"), null when any step is missing. */
    public static Object valueAt(Map<String, Object> data, String path) {
        Object current = data;
        for (String step : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(step);
        }
        return current;
    }
}
