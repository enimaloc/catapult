package fr.enimaloc.catapult.chat.command.js;

import fr.enimaloc.catapult.chat.command.ast.AssignStatement;
import fr.enimaloc.catapult.chat.command.ast.BinaryExpr;
import fr.enimaloc.catapult.chat.command.ast.CommandAst;
import fr.enimaloc.catapult.chat.command.ast.ConcatStatement;
import fr.enimaloc.catapult.chat.command.ast.ContextGetExpr;
import fr.enimaloc.catapult.chat.command.ast.Expression;
import fr.enimaloc.catapult.chat.command.ast.ForEachStatement;
import fr.enimaloc.catapult.chat.command.ast.IfStatement;
import fr.enimaloc.catapult.chat.command.ast.LiteralExpr;
import fr.enimaloc.catapult.chat.command.ast.ObjectLiteralExpr;
import fr.enimaloc.catapult.chat.command.ast.PrintStatement;
import fr.enimaloc.catapult.chat.command.ast.PropertyGetExpr;
import fr.enimaloc.catapult.chat.command.ast.ServiceCallExpr;
import fr.enimaloc.catapult.chat.command.ast.Statement;
import fr.enimaloc.catapult.chat.command.ast.ValueType;
import fr.enimaloc.catapult.chat.command.ast.VarDeclStatement;
import fr.enimaloc.catapult.chat.command.ast.VarRefExpr;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Compiles a {@link CommandAst} into a JavaScript program string that accumulates into
 * {@code let __output = "";} (returned at the end) via a {@code ctx} object exposing
 * {@code ctx.placeholder(path)}, {@code ctx.call(namespace, function, ...args)} and
 * {@code ctx.list(name)}. Statements execute in order; {@code if}/{@code for-each} bodies
 * compile to native JS {@code { }} blocks, so variables declared inside them are naturally
 * block-scoped by plain JS {@code let}/{@code const} semantics — no extra bookkeeping needed.
 *
 * <p>Every context path the ast actually references (e.g. {@code "game#name"}) gets a one-time
 * setup line at the top of the script — {@code ctx.game = ctx.game || {}; ctx.game.name =
 * ctx.placeholder("game#name");} — so the rest of the compiled body can read {@code ctx.game.name}
 * as a plain dot-chain instead of calling {@code ctx.placeholder(...)} inline. This is a pure
 * compile-time lowering: {@link SandboxExecutor}'s actual {@code ctx.placeholder}/{@code ctx.call}/
 * {@code ctx.list} contract is untouched, so hand-edited "ejected" JS written against the old
 * {@code ctx.placeholder(path)} form keeps working unchanged.
 *
 * <p>{@link #compileWithTrace(CommandAst)} additionally emits {@code __trace.var(name, value)}
 * calls after every var-decl/assign/concat/print and a {@code __trace.branch(desc, cond)} call
 * wrapping every {@code if} condition (which returns {@code cond} unchanged, so it can be used
 * inline as the condition itself) — bound by {@link SandboxExecutor}'s trace-execution path.
 */
@Component
public class JsCompiler {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*$");
    private static final Pattern NUMBER_LITERAL = Pattern.compile("^-?\\d+(\\.\\d+)?$");

    // Prototype-pollution guard: a context path segment named "__proto__" (or "constructor"/
    // "prototype", which reach the same object via ctx.game.constructor.prototype) must never be
    // emitted as a dot-chain assignment target — bracket notation does NOT neutralize "__proto__"
    // (obj["__proto__"] = x still sets the prototype), so these segments are rejected outright
    // rather than merely re-escaped.
    private static final Set<String> UNSAFE_PROPERTY_NAMES = Set.of("__proto__", "constructor", "prototype");

    public String compile(CommandAst ast) {
        return compile(ast, false);
    }

    public String compileWithTrace(CommandAst ast) {
        return compile(ast, true);
    }

    private String compile(CommandAst ast, boolean trace) {
        StringBuilder js = new StringBuilder();
        js.append("let __output = \"\";\n");
        js.append(buildContextSetup(ast));
        for (Statement statement : ast.statements()) {
            appendStatement(statement, js, trace);
        }
        js.append("return __output;\n");
        return js.toString();
    }

    private String buildContextSetup(CommandAst ast) {
        Set<String> paths = new LinkedHashSet<>();
        for (Statement statement : ast.statements()) {
            collectContextPaths(statement, paths);
        }
        if (paths.isEmpty()) return "";

        StringBuilder setup = new StringBuilder();
        Set<String> declaredPrefixes = new LinkedHashSet<>();
        for (String path : paths) {
            String[] segments = path.split("#");
            String prefix = "ctx";
            for (int i = 0; i < segments.length - 1; i++) {
                prefix = jsPropertyAccess(prefix, segments[i]);
                if (declaredPrefixes.add(prefix)) {
                    setup.append(prefix).append(" = ").append(prefix).append(" || {};\n");
                }
            }
            setup.append(ctxPropertyChain(path))
                .append(" = ctx.placeholder(\"").append(escape(path)).append("\");\n");
        }
        return setup.toString();
    }

    /** {@code "game#store#steam"} -> {@code ctx.game.store.steam}, falling back to bracket
     *  notation ({@code ctx["a b"]}) for any segment that isn't a safe JS identifier. */
    private String ctxPropertyChain(String path) {
        String chain = "ctx";
        for (String segment : path.split("#")) {
            chain = jsPropertyAccess(chain, segment);
        }
        return chain;
    }

    private String jsPropertyAccess(String base, String segment) {
        if (UNSAFE_PROPERTY_NAMES.contains(segment)) {
            throw new JsCompilationException(
                "Context path segment is not allowed (prototype pollution risk): " + segment);
        }
        return SAFE_IDENTIFIER.matcher(segment).matches()
            ? base + "." + segment
            : base + "[\"" + escape(segment) + "\"]";
    }

    private void collectContextPaths(Statement statement, Set<String> paths) {
        switch (statement) {
            case VarDeclStatement s -> collectContextPaths(s.init(), paths);
            case AssignStatement s -> collectContextPaths(s.expr(), paths);
            case ConcatStatement s -> collectContextPaths(s.expr(), paths);
            case PrintStatement s -> collectContextPaths(s.expr(), paths);
            case IfStatement s -> {
                collectContextPaths(s.condition(), paths);
                for (Statement child : s.thenBranch()) collectContextPaths(child, paths);
                for (Statement child : s.elseBranch()) collectContextPaths(child, paths);
            }
            case ForEachStatement s -> {
                for (Statement child : s.body()) collectContextPaths(child, paths);
            }
            default -> throw new IllegalArgumentException("Unhandled statement type: " + statement.typeName());
        }
    }

    private void collectContextPaths(Expression expr, Set<String> paths) {
        switch (expr) {
            case ContextGetExpr e -> paths.add(e.path());
            case ServiceCallExpr e -> {
                for (Expression arg : e.args()) collectContextPaths(arg, paths);
            }
            case BinaryExpr e -> {
                collectContextPaths(e.left(), paths);
                collectContextPaths(e.right(), paths);
            }
            case ObjectLiteralExpr e -> {
                for (Expression value : e.properties().values()) collectContextPaths(value, paths);
            }
            case PropertyGetExpr e -> collectContextPaths(e.target(), paths);
            case LiteralExpr ignored -> { }
            case VarRefExpr ignored -> { }
            default -> throw new IllegalArgumentException("Unsupported expression: " + expr.typeName());
        }
    }

    private void appendStatement(Statement statement, StringBuilder js, boolean trace) {
        switch (statement) {
            case VarDeclStatement s -> {
                validateIdentifier(s.name());
                js.append("let ").append(s.name()).append(" = ").append(compileExpr(s.init())).append(";\n");
                appendVarTrace(s.name(), js, trace);
            }
            case AssignStatement s -> {
                validateIdentifier(s.name());
                js.append(s.name()).append(" = ").append(compileExpr(s.expr())).append(";\n");
                appendVarTrace(s.name(), js, trace);
            }
            case ConcatStatement s -> {
                validateIdentifier(s.name());
                js.append(s.name()).append(" = ").append(s.name()).append(" + (")
                    .append(compileExpr(s.expr())).append(");\n");
                appendVarTrace(s.name(), js, trace);
            }
            case PrintStatement s -> {
                js.append("__output += (").append(compileExpr(s.expr())).append(");\n");
                if (trace) js.append("__trace.var(\"__output\", __output);\n");
            }
            case IfStatement s -> {
                String condJs = compileCondition(s.condition());
                if (trace) {
                    condJs = "__trace.branch(\"" + escape(describe(s.condition())) + "\", " + condJs + ")";
                }
                js.append("if (").append(condJs).append(") {\n");
                for (Statement child : s.thenBranch()) appendStatement(child, js, trace);
                if (!s.elseBranch().isEmpty()) {
                    js.append("} else {\n");
                    for (Statement child : s.elseBranch()) appendStatement(child, js, trace);
                }
                js.append("}\n");
            }
            case ForEachStatement s -> {
                if (!SAFE_IDENTIFIER.matcher(s.bindingName()).matches()) {
                    throw new JsCompilationException(
                        "Invalid loop binding name: " + s.bindingName() + " (must be a valid identifier)");
                }
                js.append("for (const ").append(s.bindingName()).append(" of ctx.list(\"")
                    .append(escape(s.listSource())).append("\")) {\n");
                appendVarTrace(s.bindingName(), js, trace);
                for (Statement child : s.body()) appendStatement(child, js, trace);
                js.append("}\n");
            }
            default -> throw new IllegalArgumentException("Unhandled statement type: " + statement.typeName());
        }
    }

    private void appendVarTrace(String name, StringBuilder js, boolean trace) {
        if (!trace) return;
        js.append("__trace.var(\"").append(escape(name)).append("\", ").append(name).append(");\n");
    }

    /** Renders a top-level {@code if} condition without the redundant outer parens {@link #compileExpr} adds for nested binary expressions. */
    private String compileCondition(BinaryExpr condition) {
        return compileExpr(condition.left()) + " " + jsOperator(condition.operator())
            + " " + compileExpr(condition.right());
    }

    private String compileExpr(Expression expr) {
        return switch (expr) {
            case LiteralExpr e -> compileLiteral(e);
            case VarRefExpr e -> {
                validateIdentifier(e.name());
                yield e.name();
            }
            case ContextGetExpr e -> ctxPropertyChain(e.path());
            case ServiceCallExpr e -> compileServiceCall(e);
            case BinaryExpr e -> "(" + compileExpr(e.left()) + " " + jsOperator(e.operator())
                + " " + compileExpr(e.right()) + ")";
            case ObjectLiteralExpr e -> compileObjectLiteral(e);
            case PropertyGetExpr e -> compileExpr(e.target()) + "[\"" + escape(e.property()) + "\"]";
            default -> throw new IllegalArgumentException("Unsupported expression: " + expr.typeName());
        };
    }

    private String compileObjectLiteral(ObjectLiteralExpr expr) {
        String entries = expr.properties().entrySet().stream()
            .map(entry -> "\"" + escape(entry.getKey()) + "\": " + compileExpr(entry.getValue()))
            .collect(Collectors.joining(", "));
        return "{" + entries + "}";
    }

    private String compileLiteral(LiteralExpr expr) {
        return switch (expr.type()) {
            case STRING -> "\"" + escape(expr.value()) + "\"";
            case NUMBER -> {
                if (!NUMBER_LITERAL.matcher(expr.value()).matches()) {
                    throw new JsCompilationException("Invalid number literal: " + expr.value());
                }
                yield expr.value();
            }
            case BOOLEAN -> {
                if (!expr.value().equals("true") && !expr.value().equals("false")) {
                    throw new JsCompilationException("Invalid boolean literal: " + expr.value());
                }
                yield expr.value();
            }
            case LIST -> throw new JsCompilationException("Literal list values are not supported in Phase 1");
            case OBJECT -> throw new JsCompilationException(
                "OBJECT is never a LiteralExpr's own type — object values are ObjectLiteralExpr, not a literal variant");
        };
    }

    private String compileServiceCall(ServiceCallExpr expr) {
        String args = expr.args().stream().map(this::compileExpr).collect(Collectors.joining(", "));
        return "ctx.call(\"" + escape(expr.namespace()) + "\", \"" + escape(expr.function()) + "\""
            + (args.isEmpty() ? "" : ", " + args) + ")";
    }

    private String jsOperator(String operator) {
        return switch (operator) {
            case "==" -> "===";
            case "!=" -> "!==";
            default -> operator;
        };
    }

    private void validateIdentifier(String name) {
        if (!SAFE_IDENTIFIER.matcher(name).matches()) {
            throw new JsCompilationException("Invalid identifier: " + name + " (must be a valid identifier)");
        }
    }

    private String describe(Expression expr) {
        return switch (expr) {
            case LiteralExpr e when e.type() == ValueType.STRING -> "\"" + e.value() + "\"";
            case LiteralExpr e -> e.value();
            case VarRefExpr e -> e.name();
            case ContextGetExpr e -> e.path();
            case ServiceCallExpr e -> e.namespace() + "#" + e.function() + "(...)";
            case BinaryExpr e -> describe(e.left()) + " " + e.operator() + " " + describe(e.right());
            default -> expr.typeName();
        };
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
