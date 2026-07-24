package fr.enimaloc.catapult.chat.command.dsl;

import fr.enimaloc.catapult.chat.command.ast.ArgGetExpr;
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
import fr.enimaloc.catapult.chat.command.ast.SettingGetExpr;
import fr.enimaloc.catapult.chat.command.ast.Statement;
import fr.enimaloc.catapult.chat.command.ast.ValueType;
import fr.enimaloc.catapult.chat.command.ast.VarDeclStatement;
import fr.enimaloc.catapult.chat.command.ast.VarRefExpr;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Generates DSL text from a {@link CommandAst} — the inverse of {@link CommandDslParser}.
 * Canonical output: {@code PrintStatement(ContextGetExpr)}/{@code (ServiceCallExpr)}/
 * {@code (VarRefExpr)}/{@code (LiteralExpr STRING)} use the compact bare-tag shorthand;
 * anything else uses the explicit {@code {print expr}} form. {@link ContextGetExpr} renders
 * as the {@code ctx.a.b} dot-chain (sugar for the "a#b" path) in every expression position, and
 * {@link PropertyGetExpr} renders as a plain dot-chain ({@code obj.prop}) — both spellings parse
 * back to the same node, so {@code parse(generate(ast)) == ast} always holds.
 */
public class CommandDslGenerator {

    public String generate(CommandAst ast) {
        StringBuilder out = new StringBuilder();
        for (Statement statement : ast.statements()) {
            out.append(generateStatement(statement));
        }
        return out.toString();
    }

    private String generateStatement(Statement statement) {
        return switch (statement) {
            case PrintStatement s -> generatePrint(s);
            case VarDeclStatement s -> "{var " + s.name() + " = " + generateExpr(s.init()) + "}";
            case AssignStatement s -> "{" + s.name() + " = " + generateExpr(s.expr()) + "}";
            case ConcatStatement s ->
                "{" + s.name() + " = " + s.name() + " + " + generateExpr(s.expr()) + "}";
            case IfStatement s -> generateIf(s);
            case ForEachStatement s -> generateForEach(s);
            default -> throw new IllegalArgumentException("Unhandled statement type: " + statement.typeName());
        };
    }

    private String generatePrint(PrintStatement s) {
        Expression expr = s.expr();
        if (expr instanceof LiteralExpr literal && literal.type() == ValueType.STRING) {
            return literal.value();
        }
        if (expr instanceof ContextGetExpr contextGet) {
            return "{ctx." + contextGet.path().replace('#', '.') + "}";
        }
        if (expr instanceof ServiceCallExpr call) {
            return "{" + call.namespace() + "#" + call.function() + "(" + generateArgs(call.args()) + ")}";
        }
        if (expr instanceof VarRefExpr varRef) {
            return "{" + varRef.name() + "}";
        }
        return "{print " + generateExpr(expr) + "}";
    }

    private String generateIf(IfStatement s) {
        StringBuilder sb = new StringBuilder();
        BinaryExpr condition = s.condition();
        sb.append("{if ").append(generateExpr(condition.left())).append(" ")
            .append(condition.operator()).append(" ")
            .append(generateExpr(condition.right())).append("}");
        for (Statement child : s.thenBranch()) sb.append(generateStatement(child));
        if (!s.elseBranch().isEmpty()) {
            sb.append("{else}");
            for (Statement child : s.elseBranch()) sb.append(generateStatement(child));
        }
        sb.append("{/if}");
        return sb.toString();
    }

    private String generateForEach(ForEachStatement s) {
        StringBuilder sb = new StringBuilder();
        sb.append("{for ").append(s.bindingName()).append(" in ").append(s.listSource()).append("}");
        for (Statement child : s.body()) sb.append(generateStatement(child));
        sb.append("{/for}");
        return sb.toString();
    }

    private String generateArgs(List<Expression> args) {
        return args.stream().map(this::generateExpr).collect(Collectors.joining(", "));
    }

    private String generateExpr(Expression expr) {
        return switch (expr) {
            case LiteralExpr e when e.type() == ValueType.STRING -> "\"" + e.value() + "\"";
            case LiteralExpr e -> e.value();
            case VarRefExpr e -> e.name();
            case ContextGetExpr e -> "ctx." + e.path().replace('#', '.');
            case SettingGetExpr e -> "ctx.settings." + e.key();
            case ArgGetExpr e -> "arg(" + e.index() + ")";
            case ServiceCallExpr e -> e.namespace() + "#" + e.function() + "(" + generateArgs(e.args()) + ")";
            case BinaryExpr e -> generateExpr(e.left()) + " " + e.operator() + " " + generateExpr(e.right());
            case ObjectLiteralExpr e -> generateObjectLiteral(e);
            case PropertyGetExpr e -> generateExpr(e.target()) + "." + e.property();
            default -> throw new IllegalArgumentException("Unsupported expression: " + expr.typeName());
        };
    }

    private String generateObjectLiteral(ObjectLiteralExpr expr) {
        String entries = expr.properties().entrySet().stream()
            .map(entry -> entry.getKey() + ": " + generateExpr(entry.getValue()))
            .collect(Collectors.joining(", "));
        return "{" + entries + "}";
    }
}
