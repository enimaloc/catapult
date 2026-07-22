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
import fr.enimaloc.catapult.chat.command.ast.PrintStatement;
import fr.enimaloc.catapult.chat.command.ast.ServiceCallExpr;
import fr.enimaloc.catapult.chat.command.ast.Statement;
import fr.enimaloc.catapult.chat.command.ast.ValueType;
import fr.enimaloc.catapult.chat.command.ast.VarDeclStatement;
import fr.enimaloc.catapult.chat.command.ast.VarRefExpr;
import org.springframework.stereotype.Component;

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
 * <p>{@link #compileWithTrace(CommandAst)} additionally emits {@code __trace.var(name, value)}
 * calls after every var-decl/assign/concat/print and a {@code __trace.branch(desc, cond)} call
 * wrapping every {@code if} condition (which returns {@code cond} unchanged, so it can be used
 * inline as the condition itself) — bound by {@link SandboxExecutor}'s trace-execution path.
 */
@Component
public class JsCompiler {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*$");
    private static final Pattern NUMBER_LITERAL = Pattern.compile("^-?\\d+(\\.\\d+)?$");

    public String compile(CommandAst ast) {
        return compile(ast, false);
    }

    public String compileWithTrace(CommandAst ast) {
        return compile(ast, true);
    }

    private String compile(CommandAst ast, boolean trace) {
        StringBuilder js = new StringBuilder();
        js.append("let __output = \"\";\n");
        for (Statement statement : ast.statements()) {
            appendStatement(statement, js, trace);
        }
        js.append("return __output;\n");
        return js.toString();
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
            case ContextGetExpr e -> "ctx.placeholder(\"" + escape(e.path()) + "\")";
            case ServiceCallExpr e -> compileServiceCall(e);
            case BinaryExpr e -> "(" + compileExpr(e.left()) + " " + jsOperator(e.operator())
                + " " + compileExpr(e.right()) + ")";
            default -> throw new IllegalArgumentException("Unsupported expression: " + expr.typeName());
        };
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
