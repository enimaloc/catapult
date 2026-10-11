package fr.enimaloc.catapult.controller.twitchat;

import fr.enimaloc.catapult.common.dto.twitchat.TwitchatActionType;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatEventActionDto;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatEvents;
import fr.enimaloc.catapult.common.twitchat.TwitchatCatalog;
import fr.enimaloc.catapult.common.twitchat.TwitchatPayloadValidator;
import fr.enimaloc.catapult.dto.integration.Attrs;
import fr.enimaloc.catapult.dto.integration.IntegrationNode;
import fr.enimaloc.catapult.dto.integration.IntegrationNode.Button;
import fr.enimaloc.catapult.dto.integration.IntegrationNode.Container;
import fr.enimaloc.catapult.dto.integration.IntegrationNode.Hidden;
import fr.enimaloc.catapult.dto.integration.IntegrationNode.Select;
import fr.enimaloc.catapult.dto.integration.IntegrationNode.Text;
import fr.enimaloc.catapult.dto.integration.IntegrationNode.TextField;
import fr.enimaloc.catapult.dto.integration.Message;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static fr.enimaloc.catapult.dto.integration.Message.key;

/**
 * The Twitchat card's event editor as integration nodes: per event, its payloads, each its
 * catalog action's form (fields as data-field paths into the payload's data) or its raw JSON.
 * twitchat-events.js reads it all back from the classes and data-field attributes set here.
 */
public final class TwitchatEventForms {

    private static final String PREFIX = "channel.connections.twitchat.";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern ACTION_URL = Pattern.compile("\\{\\{\\s*action\\s*:\\s*([A-Z_]+)\\s*\\}\\}");

    private final TwitchatCatalog catalog;
    private final TwitchatPayloadValidator validator;

    public TwitchatEventForms(TwitchatCatalog catalog) {
        this.catalog = catalog;
        this.validator = new TwitchatPayloadValidator(catalog);
    }

    public IntegrationNode eventsSection(Map<String, List<TwitchatEventActionDto>> events) {
        List<IntegrationNode.Collapse.Item> items = new ArrayList<>();
        for (String event : TwitchatEvents.ALL) {
            List<TwitchatEventActionDto> payloads = events.getOrDefault(event, List.of());
            List<IntegrationNode> cards = payloads.stream().map(payload -> payloadCard(event, payload)).toList();
            items.add(new IntegrationNode.Collapse.Item(Attrs.cls("twitchat-event"), event,
                    key(PREFIX + "event." + event, payloads.size()), List.of(
                    new Hidden(Attrs.cls("twitchat-event-type"), event),
                    new Text(Attrs.cls("twitchat-event-variables"), true, key(PREFIX + "event.variables",
                            String.join(", ", variables(event)))),
                    new Container(Attrs.cls("twitchat-payloads"), Container.Kind.DIV, cards),
                    new Container(Attrs.cls("twitchat-event-actions"), Container.Kind.DIV, List.of(
                            new Select(Attrs.cls("twitchat-add-action"), key(PREFIX + "event.add"), null, actionOptions(), null),
                            new Button(Attrs.cls("twitchat-test-all-btn"), Button.Kind.TEXT, key(PREFIX + "event.test_all")),
                            new Button(Attrs.cls("twitchat-save-btn"), Button.Kind.FILLED, key(PREFIX + "event.save")))),
                    new Text(Attrs.cls("twitchat-event-errors"), true, Message.literal("")))));
        }
        return new IntegrationNode.Collapse(Attrs.id("twitchat-events"), items);
    }

    /** A payload not saved yet: the action's field defaults. UnknownAction unless in the catalog. */
    public TwitchatEventActionDto newPayload(String action) {
        TwitchatCatalog.Action entry = catalog.action(action).orElseThrow(() -> new UnknownAction(action));
        Map<String, Object> data = new LinkedHashMap<>();
        for (TwitchatCatalog.Field field : orEmpty(entry.fields())) {
            if (field.path() != null && field.defaultValue() != null) {
                setPath(data, field.path(), field.defaultValue());
            }
        }
        return new TwitchatEventActionDto(action, entry.rawOnly() ? "RAW" : "FORM", JSON.writeValueAsString(data), true);
    }

    /**
     * A payload's card. One whose action left the catalog (regenerated since it was saved) keeps
     * its raw JSON only, under its stored key, instead of breaking the whole page.
     */
    public IntegrationNode payloadCard(String event, TwitchatEventActionDto payload) {
        TwitchatCatalog.Action action = catalog.action(payload.action()).orElseGet(() ->
                new TwitchatCatalog.Action(payload.action(), Map.of(), null, null, "raw", List.of()));
        boolean raw = action.rawOnly() || "RAW".equals(payload.mode());
        Map<String, Object> data = dataOf(payload.dataJson());
        return new Container(Attrs.cls("twitchat-payload"), Container.Kind.DIV, List.of(
                new Hidden(Attrs.cls("twitchat-payload-action"), action.key()),
                new Hidden(Attrs.cls("twitchat-payload-mode"), raw ? "RAW" : payload.mode()),
                new Hidden(Attrs.cls("twitchat-payload-names"), JSON.writeValueAsString(orderedNames(action.names()))),
                new Container(Attrs.cls("twitchat-payload-header"), Container.Kind.DIV, List.of(
                        new Text(Attrs.cls("twitchat-payload-title"), false, Message.literal(actionLabel(action))),
                        new IntegrationNode.Switch(Attrs.cls("twitchat-payload-enabled"), payload.enabled(), key(PREFIX + "payload.enabled")),
                        new Button(Attrs.cls("twitchat-payload-up"), Button.Kind.TEXT, key(PREFIX + "payload.up")),
                        new Button(Attrs.cls("twitchat-payload-down"), Button.Kind.TEXT, key(PREFIX + "payload.down")),
                        new Button(Attrs.cls("twitchat-payload-raw-btn").hiddenIf(action.rawOnly()), Button.Kind.TEXT,
                                key(PREFIX + (raw ? "payload.form" : "payload.raw"))),
                        new Button(Attrs.cls("twitchat-payload-test"), Button.Kind.TEXT, key(PREFIX + "payload.test")),
                        new Button(Attrs.cls("twitchat-payload-delete"), Button.Kind.TEXT, key(PREFIX + "payload.delete")))),
                new Text(Attrs.cls("twitchat-payload-unavailable").hiddenIf(true), true, key(PREFIX + "payload.unavailable")),
                new Container(Attrs.cls("twitchat-payload-form").hiddenIf(raw), Container.Kind.DIV, fields(event, orEmpty(action.fields()), data)),
                new Container(Attrs.cls("twitchat-payload-raw").hiddenIf(!raw), Container.Kind.DIV, List.of(
                        new TextField(Attrs.cls("twitchat-payload-raw-json"), key(PREFIX + "payload.json"), "text",
                                pretty(payload.dataJson()), true))),
                new Text(Attrs.cls("twitchat-payload-errors"), true, Message.literal(""))));
    }

    public List<IntegrationNode> fields(String event, List<TwitchatCatalog.Field> fields, Map<String, Object> data) {
        List<IntegrationNode> nodes = new ArrayList<>();
        for (TwitchatCatalog.Field field : fields) {
            nodes.add(field(event, field, data));
        }
        return nodes;
    }

    private IntegrationNode field(String event, TwitchatCatalog.Field field, Map<String, Object> data) {
        Message label = field.labelKey() != null ? key(field.labelKey()) : Message.literal(field.label());
        Object value = field.path() == null ? null : TwitchatPayloadValidator.valueAt(data, field.path());
        Attrs attrs = Attrs.field(field.path());
        return switch (field.type()) {
            case "textarea" -> new TextField(attrs, label, "text", value, true);
            case "number" -> new TextField(attrs, label, "number", value, false);
            case "switch" -> new IntegrationNode.Switch(attrs, Boolean.TRUE.equals(value), label);
            case "json" -> new TextField(attrs.withClass("json-field"), label, "text",
                    value == null ? null : JSON.writeValueAsString(value), true);
            case "select" -> new Select(attrs, label, value == null ? null : value.toString(),
                    selectOptions(field, value), field.source() == null ? null : JSON.writeValueAsString(field.source()));
            case "repeater" -> repeater(event, field, attrs, label, value);
            case "catapult-action" -> catapultAction(event, label, data);
            default -> new TextField(attrs, label, "text", value, false);
        };
    }

    private List<Select.Option> selectOptions(TwitchatCatalog.Field field, Object value) {
        List<Select.Option> options = new ArrayList<>();
        if (field.options() != null) {
            for (TwitchatCatalog.Option option : field.options()) {
                options.add(new Select.Option(option.value(),
                        option.labelKey() != null ? key(option.labelKey()) : Message.literal(option.label())));
            }
        }
        // A live list (or a value from before its choices changed) keeps the stored value selectable.
        if (value != null && options.stream().noneMatch(option -> option.value().equals(value.toString()))) {
            options.add(new Select.Option(value.toString(), Message.literal(value.toString())));
        }
        return options;
    }

    private IntegrationNode repeater(String event, TwitchatCatalog.Field field, Attrs attrs, Message label, Object value) {
        List<List<IntegrationNode>> rows = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object row : list) {
                @SuppressWarnings("unchecked")
                Map<String, Object> rowData = row instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
                rows.add(fields(event, orEmpty(field.fields()), rowData));
            }
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        orEmpty(field.fields()).stream().filter(f -> f.path() != null && f.defaultValue() != null)
                .forEach(f -> setPath(defaults, f.path(), f.defaultValue()));
        return new IntegrationNode.Repeater(attrs, label, rows, fields(event, orEmpty(field.fields()), defaults));
    }

    /** Points a button at one of this event's Catapult actions: twitchat-events.js writes its URL. */
    private IntegrationNode catapultAction(String event, Message label, Map<String, Object> row) {
        List<Select.Option> options = new ArrayList<>(List.of(new Select.Option("", key(PREFIX + "payload.no_catapult_action"))));
        for (TwitchatActionType type : TwitchatEvents.ACTIONS.getOrDefault(event, List.of())) {
            options.add(new Select.Option(type.name(), key(PREFIX + "catapult_action." + type.name())));
        }
        Object url = row.get("url");
        Matcher matcher = url instanceof String text ? ACTION_URL.matcher(text) : null;
        String selected = matcher != null && matcher.find() ? matcher.group(1) : "";
        return new Select(Attrs.cls("twitchat-catapult-action"), label, selected, options, null);
    }

    /** An action the catalog doesn't know: no payload can be started from it. */
    public static final class UnknownAction extends IllegalArgumentException {
        public UnknownAction(String action) {
            super("Unknown Twitchat action: " + action);
        }
    }

    private List<Select.Option> actionOptions() {
        return catalog.actions().stream()
                .map(action -> new Select.Option(action.key(), Message.literal(actionLabel(action))))
                .toList();
    }

    /** Action names in a fixed order (stable first): the catalog's map is unordered. */
    private static Map<String, String> orderedNames(Map<String, String> names) {
        Map<String, String> ordered = new LinkedHashMap<>();
        for (String protocol : List.of("stable", "beta")) {
            if (names.containsKey(protocol)) {
                ordered.put(protocol, names.get(protocol));
            }
        }
        names.forEach(ordered::putIfAbsent);
        return ordered;
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static List<String> variables(String event) {
        List<String> names = new ArrayList<>(List.of("{{baseUrl}}", "{{channel}}"));
        TwitchatEvents.VARIABLES.getOrDefault(event, List.of()).forEach(name -> names.add("{{" + name + "}}"));
        return names;
    }

    /** The action's description's first line, else its key. */
    public static String actionLabel(TwitchatCatalog.Action action) {
        String description = action.description() == null ? "" : action.description().lines().findFirst().orElse("").trim();
        return description.isEmpty() ? action.key() : description + " (" + action.key() + ")";
    }

    private Map<String, Object> dataOf(String dataJson) {
        try {
            return validator.parse(dataJson);
        } catch (IllegalArgumentException e) {
            return Map.of();
        }
    }

    private static String pretty(String dataJson) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(JSON.readValue(dataJson, Object.class));
        } catch (RuntimeException e) {
            return dataJson;
        }
    }

    @SuppressWarnings("unchecked")
    private static void setPath(Map<String, Object> target, String path, Object value) {
        String[] steps = path.split("\\.");
        Map<String, Object> current = target;
        for (int i = 0; i < steps.length - 1; i++) {
            current = (Map<String, Object>) current.computeIfAbsent(steps[i], ignored -> new LinkedHashMap<String, Object>());
        }
        current.put(steps[steps.length - 1], value);
    }
}
