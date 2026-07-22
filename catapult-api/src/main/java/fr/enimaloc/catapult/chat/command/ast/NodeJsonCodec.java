package fr.enimaloc.catapult.chat.command.ast;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a {@link CommandAst} to/from JSON.
 *
 * <p>Deliberately avoids Jackson polymorphic-type annotations ({@code @JsonTypeInfo} etc.):
 * those require a fixed permits-list on a sealed hierarchy, which would defeat the
 * registry-based extensibility goal of the AST (new node types can be registered later
 * without editing annotations here). Instead, each node is serialized as a JSON object
 * tagged by its {@link CommandNode#typeName()} and dispatched manually via the two
 * {@code switch} blocks below — the one place (besides the registries) that knows every
 * built-in type.
 */
public class NodeJsonCodec {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    public String toJson(CommandAst ast) {
        List<Map<String, Object>> nodes = ast.nodes().stream().map(this::toMap).toList();
        return mapper.writeValueAsString(Map.of("nodes", nodes));
    }

    @SuppressWarnings("unchecked")
    public CommandAst fromJson(String json) {
        Map<String, Object> root = mapper.readValue(json, Map.class);
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) root.get("nodes");
        return new CommandAst(nodes.stream().map(this::fromMap).toList());
    }

    private Map<String, Object> toMap(CommandNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", node.typeName());
        switch (node) {
            case LiteralNode n -> map.put("text", n.text());
            case PlaceholderNode n -> map.put("path", n.path());
            case ServiceCallNode n -> {
                map.put("namespace", n.namespace());
                map.put("function", n.function());
                map.put("args", n.args().stream().map(this::toMap).toList());
            }
            case IfNode n -> {
                map.put("left", toMap(n.left()));
                map.put("operator", n.operator());
                map.put("right", toMap(n.right()));
                map.put("then", n.thenBranch().stream().map(this::toMap).toList());
                map.put("else", n.elseBranch().stream().map(this::toMap).toList());
            }
            case ForEachNode n -> {
                map.put("bindingName", n.bindingName());
                map.put("listSource", n.listSource());
                map.put("body", n.body().stream().map(this::toMap).toList());
            }
            default -> throw new IllegalArgumentException("Unhandled node type: " + node.typeName());
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private CommandNode fromMap(Map<String, Object> map) {
        String type = (String) map.get("type");
        return switch (type) {
            case "literal" -> new LiteralNode((String) map.get("text"));
            case "placeholder" -> new PlaceholderNode((String) map.get("path"));
            case "service-call" -> new ServiceCallNode(
                (String) map.get("namespace"),
                (String) map.get("function"),
                fromMapList((List<Map<String, Object>>) map.get("args")));
            case "if" -> new IfNode(
                fromMap((Map<String, Object>) map.get("left")),
                (String) map.get("operator"),
                fromMap((Map<String, Object>) map.get("right")),
                fromMapList((List<Map<String, Object>>) map.get("then")),
                fromMapList((List<Map<String, Object>>) map.get("else")));
            case "for-each" -> new ForEachNode(
                (String) map.get("bindingName"),
                (String) map.get("listSource"),
                fromMapList((List<Map<String, Object>>) map.get("body")));
            default -> throw new IllegalArgumentException("Unknown node type: " + type);
        };
    }

    private List<CommandNode> fromMapList(List<Map<String, Object>> maps) {
        List<CommandNode> nodes = new ArrayList<>();
        for (Map<String, Object> m : maps) nodes.add(fromMap(m));
        return nodes;
    }
}
