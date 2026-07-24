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
 * <p>Deliberately avoids Jackson polymorphic-type annotations: each node is serialized as a
 * JSON object tagged by its {@link Node#typeName()} and dispatched manually via the switch
 * blocks below, keeping the registry-based extensibility goal of the AST intact (new node
 * types can be added without editing annotations here).
 */
public class NodeJsonCodec {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    public String toJson(CommandAst ast) {
        List<Map<String, Object>> statements = ast.statements().stream().map(this::statementToMap).toList();
        return mapper.writeValueAsString(Map.of("statements", statements));
    }

    @SuppressWarnings("unchecked")
    public CommandAst fromJson(String json) {
        Map<String, Object> root = mapper.readValue(json, Map.class);
        List<Map<String, Object>> statements = (List<Map<String, Object>>) root.get("statements");
        return new CommandAst(statementsFromMaps(statements));
    }

    /**
     * Structurally detects whether a persisted {@code ast} JSON blob is still in the pre-rewrite
     * expression-tree shape (a root {@code "nodes"} array) rather than the current
     * statement-based shape (a root {@code "statements"} array). Used by
     * {@code ChatCommandAstBackfill} to find stale rows that need re-deriving from their
     * template — this deliberately does not deserialize the old shape, it only inspects the
     * raw JSON structure.
     */
    @SuppressWarnings("unchecked")
    public boolean isOldShape(String json) {
        Map<String, Object> root = mapper.readValue(json, Map.class);
        return root.containsKey("nodes") && !root.containsKey("statements");
    }

    // ---- Statements ----

    private Map<String, Object> statementToMap(Statement statement) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", statement.typeName());
        switch (statement) {
            case VarDeclStatement s -> {
                map.put("name", s.name());
                map.put("valueType", s.type().name());
                map.put("init", expressionToMap(s.init()));
            }
            case AssignStatement s -> {
                map.put("name", s.name());
                map.put("expr", expressionToMap(s.expr()));
            }
            case ConcatStatement s -> {
                map.put("name", s.name());
                map.put("expr", expressionToMap(s.expr()));
            }
            case PrintStatement s -> map.put("expr", expressionToMap(s.expr()));
            case IfStatement s -> {
                map.put("condition", expressionToMap(s.condition()));
                map.put("then", s.thenBranch().stream().map(this::statementToMap).toList());
                map.put("else", s.elseBranch().stream().map(this::statementToMap).toList());
            }
            case ForEachStatement s -> {
                map.put("bindingName", s.bindingName());
                map.put("listSource", s.listSource());
                map.put("body", s.body().stream().map(this::statementToMap).toList());
            }
            default -> throw new IllegalArgumentException("Unhandled statement type: " + statement.typeName());
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private Statement statementFromMap(Map<String, Object> map) {
        String type = (String) map.get("type");
        return switch (type) {
            case "var-decl" -> new VarDeclStatement(
                (String) map.get("name"),
                ValueType.valueOf((String) map.get("valueType")),
                expressionFromMap((Map<String, Object>) map.get("init")));
            case "assign" -> new AssignStatement(
                (String) map.get("name"),
                expressionFromMap((Map<String, Object>) map.get("expr")));
            case "concat" -> new ConcatStatement(
                (String) map.get("name"),
                expressionFromMap((Map<String, Object>) map.get("expr")));
            case "print" -> new PrintStatement(expressionFromMap((Map<String, Object>) map.get("expr")));
            case "if" -> new IfStatement(
                (BinaryExpr) expressionFromMap((Map<String, Object>) map.get("condition")),
                statementsFromMaps((List<Map<String, Object>>) map.get("then")),
                statementsFromMaps((List<Map<String, Object>>) map.get("else")));
            case "for-each" -> new ForEachStatement(
                (String) map.get("bindingName"),
                (String) map.get("listSource"),
                statementsFromMaps((List<Map<String, Object>>) map.get("body")));
            default -> throw new IllegalArgumentException("Unknown statement type: " + type);
        };
    }

    private List<Statement> statementsFromMaps(List<Map<String, Object>> maps) {
        List<Statement> statements = new ArrayList<>();
        for (Map<String, Object> m : maps) statements.add(statementFromMap(m));
        return statements;
    }

    // ---- Expressions ----

    private Map<String, Object> expressionToMap(Expression expression) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", expression.typeName());
        switch (expression) {
            case LiteralExpr e -> {
                map.put("value", e.value());
                map.put("valueType", e.type().name());
            }
            case VarRefExpr e -> map.put("name", e.name());
            case ContextGetExpr e -> map.put("path", e.path());
            case ServiceCallExpr e -> {
                map.put("namespace", e.namespace());
                map.put("function", e.function());
                map.put("args", e.args().stream().map(this::expressionToMap).toList());
            }
            case BinaryExpr e -> {
                map.put("left", expressionToMap(e.left()));
                map.put("operator", e.operator());
                map.put("right", expressionToMap(e.right()));
            }
            case ObjectLiteralExpr e -> {
                Map<String, Object> properties = new LinkedHashMap<>();
                e.properties().forEach((key, value) -> properties.put(key, expressionToMap(value)));
                map.put("properties", properties);
            }
            case PropertyGetExpr e -> {
                map.put("target", expressionToMap(e.target()));
                map.put("property", e.property());
            }
            case SettingGetExpr e -> map.put("key", e.key());
            default -> throw new IllegalArgumentException("Unhandled expression type: " + expression.typeName());
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private Expression expressionFromMap(Map<String, Object> map) {
        String type = (String) map.get("type");
        return switch (type) {
            case "literal" ->
                new LiteralExpr((String) map.get("value"), ValueType.valueOf((String) map.get("valueType")));
            case "var-ref" -> new VarRefExpr((String) map.get("name"));
            case "context-get" -> new ContextGetExpr((String) map.get("path"));
            case "service-call" -> new ServiceCallExpr(
                (String) map.get("namespace"),
                (String) map.get("function"),
                expressionsFromMaps((List<Map<String, Object>>) map.get("args")));
            case "binary" -> new BinaryExpr(
                expressionFromMap((Map<String, Object>) map.get("left")),
                (String) map.get("operator"),
                expressionFromMap((Map<String, Object>) map.get("right")));
            case "object-literal" -> {
                Map<String, Object> rawProperties = (Map<String, Object>) map.get("properties");
                Map<String, Expression> properties = new LinkedHashMap<>();
                rawProperties.forEach((key, value) -> properties.put(key, expressionFromMap((Map<String, Object>) value)));
                yield new ObjectLiteralExpr(properties);
            }
            case "property-get" -> new PropertyGetExpr(
                expressionFromMap((Map<String, Object>) map.get("target")),
                (String) map.get("property"));
            case "setting-get" -> new SettingGetExpr((String) map.get("key"));
            default -> throw new IllegalArgumentException("Unknown expression type: " + type);
        };
    }

    private List<Expression> expressionsFromMaps(List<Map<String, Object>> maps) {
        List<Expression> expressions = new ArrayList<>();
        for (Map<String, Object> m : maps) expressions.add(expressionFromMap(m));
        return expressions;
    }
}
