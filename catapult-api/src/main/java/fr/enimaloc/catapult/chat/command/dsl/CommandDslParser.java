package fr.enimaloc.catapult.chat.command.dsl;

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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Recursive-descent parser for the imperative command DSL — see
 * docs/specs/2026-07-21-chat-command-block-dsl-design.md#text-dsl-grammar.
 * Statements: literal text (implicit print), {@code {var name = expr}}, {@code {name = expr}},
 * {@code {name = name + expr}} (concat), {@code {print expr}}, {@code {if L OP R}...{else}...{/if}},
 * {@code {for x in list}...{/for}}. Backward-compat shorthand: bare {@code {path}} (context path,
 * contains '#'), bare {@code {name}} (variable reference, no '#'), bare {@code {ns#fn(args)}}
 * (service call) all sugar for an implicit print.
 */
public class CommandDslParser {

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern NUMBER = Pattern.compile("^-?\\d+(\\.\\d+)?$");
    private static final List<String> COMPARISON_OPERATORS = List.of("!=", "==", "<=", ">=", "<", ">");

    public CommandAst parse(String text) {
        DslCursor cursor = new DslCursor(text);
        return new CommandAst(parseStatements(cursor));
    }

    private List<Statement> parseStatements(DslCursor cursor, String... stopTags) {
        List<Statement> statements = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        while (!cursor.atEnd()) {
            if (looksAheadAtAny(cursor, stopTags)) break;
            char c = cursor.peek();
            if (c == '{') {
                if (!literal.isEmpty()) {
                    statements.add(new PrintStatement(new LiteralExpr(literal.toString(), ValueType.STRING)));
                    literal.setLength(0);
                }
                statements.add(parseTag(cursor));
            } else {
                literal.append(cursor.next());
            }
        }
        if (!literal.isEmpty()) {
            statements.add(new PrintStatement(new LiteralExpr(literal.toString(), ValueType.STRING)));
        }
        return statements;
    }

    private boolean looksAheadAt(DslCursor cursor, String tag) {
        int start = cursor.position();
        return cursor.length() - start >= tag.length()
            && cursor.substring(start, start + tag.length()).equals(tag);
    }

    private boolean looksAheadAtAny(DslCursor cursor, String... tags) {
        for (String tag : tags) {
            if (looksAheadAt(cursor, tag)) return true;
        }
        return false;
    }

    private Statement parseTag(DslCursor cursor) {
        int openPos = cursor.position();
        cursor.next(); // consume '{'
        int start = cursor.position();
        int depth = 1;
        boolean inQuotes = false;
        boolean escaped = false;
        while (!cursor.atEnd() && depth > 0) {
            char c = cursor.next();
            if (escaped) {
                escaped = false;
            } else if (inQuotes && c == '\\') {
                escaped = true;
            } else if (c == '"') {
                inQuotes = !inQuotes;
            } else if (!inQuotes) {
                if (c == '{') depth++;
                else if (c == '}') depth--;
            }
        }
        if (depth > 0) {
            throw new CommandDslParseException("Unclosed '{' starting at position " + openPos);
        }
        String inner = cursor.substring(start, cursor.position() - 1);

        if (inner.startsWith("var ")) {
            return parseVarDecl(inner.substring(4).trim());
        }
        if (inner.startsWith("if ")) {
            return parseIf(cursor, inner.substring(3).trim());
        }
        if (inner.startsWith("for ")) {
            return parseFor(cursor, inner.substring(4).trim());
        }
        if (inner.startsWith("print ")) {
            return new PrintStatement(parseExpression(inner.substring(6).trim()));
        }

        int eq = findAssignmentEquals(inner);
        if (eq > 0) {
            String lhs = inner.substring(0, eq).trim();
            if (IDENTIFIER.matcher(lhs).matches()) {
                String rhs = inner.substring(eq + 1).trim();
                String concatPrefix = lhs + " + ";
                if (rhs.startsWith(concatPrefix)) {
                    return new ConcatStatement(lhs, parseExpression(rhs.substring(concatPrefix.length()).trim()));
                }
                return new AssignStatement(lhs, parseExpression(rhs));
            }
        }

        // Backward-compat shorthand: a bare {path}, {path|fallback}, {name} or {ns#fn(args)}
        // tag is sugar for an implicit print, exactly like the pre-Phase-1 template syntax.
        ServiceCallExpr call = tryParseServiceCall(inner);
        if (call != null) {
            return new PrintStatement(call);
        }
        int bar = inner.indexOf('|');
        String path = bar < 0 ? inner : inner.substring(0, bar);
        if (path.contains("#")) {
            return new PrintStatement(new ContextGetExpr(path));
        }
        if (IDENTIFIER.matcher(path).matches()) {
            return new PrintStatement(new VarRefExpr(path));
        }
        throw new CommandDslParseException("Malformed tag: " + inner);
    }

    private Statement parseVarDecl(String rest) {
        int eq = findAssignmentEquals(rest);
        if (eq <= 0) {
            throw new CommandDslParseException("Malformed {var} declaration, expected 'name = expr': " + rest);
        }
        String name = rest.substring(0, eq).trim();
        if (!IDENTIFIER.matcher(name).matches()) {
            throw new CommandDslParseException("Invalid variable name in {var} declaration: " + name);
        }
        Expression init = parseExpression(rest.substring(eq + 1).trim());
        ValueType type = init instanceof LiteralExpr literal ? literal.type() : ValueType.STRING;
        return new VarDeclStatement(name, type, init);
    }

    private Statement parseIf(DslCursor cursor, String condition) {
        OperatorMatch match = findComparisonOperator(condition);
        Expression left = parseExpression(condition.substring(0, match.index()).trim());
        Expression right = parseExpression(condition.substring(match.index() + match.operator().length()).trim());
        BinaryExpr binary = new BinaryExpr(left, match.operator(), right);

        List<Statement> thenBranch = parseStatements(cursor, "{else}", "{/if}");
        List<Statement> elseBranch = List.of();
        if (looksAheadAt(cursor, "{else}")) {
            cursor.position(cursor.position() + "{else}".length());
            elseBranch = parseStatements(cursor, "{/if}");
        }
        if (!looksAheadAt(cursor, "{/if}")) {
            throw new CommandDslParseException("Unclosed '{if}' block, expected '{/if}'");
        }
        cursor.position(cursor.position() + "{/if}".length());
        return new IfStatement(binary, thenBranch, elseBranch);
    }

    private Statement parseFor(DslCursor cursor, String header) {
        String[] parts = header.split(" in ", 2);
        if (parts.length != 2) {
            throw new CommandDslParseException("Malformed {for} header, expected 'x in list': " + header);
        }
        String bindingName = parts[0].trim();
        String listSource = parts[1].trim();
        List<Statement> body = parseStatements(cursor, "{/for}");
        if (!looksAheadAt(cursor, "{/for}")) {
            throw new CommandDslParseException("Unclosed '{for}' block, expected '{/for}'");
        }
        cursor.position(cursor.position() + "{/for}".length());
        return new ForEachStatement(bindingName, listSource, body);
    }

    private Expression parseExpression(String token) {
        String text = token.trim();
        if (text.startsWith("\"") && text.endsWith("\"") && text.length() >= 2) {
            return new LiteralExpr(text.substring(1, text.length() - 1), ValueType.STRING);
        }
        if (text.equals("true") || text.equals("false")) {
            return new LiteralExpr(text, ValueType.BOOLEAN);
        }
        if (NUMBER.matcher(text).matches()) {
            return new LiteralExpr(text, ValueType.NUMBER);
        }
        if (text.startsWith("get(") && text.endsWith(")")) {
            return new ContextGetExpr(text.substring(4, text.length() - 1).trim());
        }
        ServiceCallExpr call = tryParseServiceCall(text);
        if (call != null) {
            return call;
        }
        if (text.contains("#")) {
            return new ContextGetExpr(text);
        }
        if (!IDENTIFIER.matcher(text).matches()) {
            throw new CommandDslParseException("Malformed expression: " + token);
        }
        return new VarRefExpr(text);
    }

    private ServiceCallExpr tryParseServiceCall(String text) {
        int parenIdx = text.indexOf('(');
        if (parenIdx > 0 && text.endsWith(")") && text.contains("#")) {
            String head = text.substring(0, parenIdx);
            String[] parts = head.split("#", 2);
            if (parts.length == 2) {
                String argsText = text.substring(parenIdx + 1, text.length() - 1);
                return new ServiceCallExpr(parts[0], parts[1], parseArgs(argsText));
            }
        }
        return null;
    }

    private List<Expression> parseArgs(String argsText) {
        List<Expression> args = new ArrayList<>();
        if (argsText.isBlank()) return args;
        for (String rawArg : splitTopLevelArgs(argsText)) {
            args.add(parseExpression(rawArg.trim()));
        }
        return args;
    }

    /** Splits a comma-separated argument list on top-level commas, ignoring commas inside quotes. */
    private List<String> splitTopLevelArgs(String argsText) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < argsText.length(); i++) {
            char c = argsText.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
            } else if (c == ',' && !inQuotes) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    private int findAssignmentEquals(String inner) {
        boolean inQuotes = false;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '"') { inQuotes = !inQuotes; continue; }
            if (inQuotes) continue;
            if (c == '=') {
                if (i + 1 < inner.length() && inner.charAt(i + 1) == '=') { i++; continue; }
                if (i > 0 && inner.charAt(i - 1) == '=') continue;
                return i;
            }
        }
        return -1;
    }

    private record OperatorMatch(int index, String operator) {}

    private OperatorMatch findComparisonOperator(String condition) {
        int bestIndex = -1;
        String bestOp = null;
        for (String op : COMPARISON_OPERATORS) {
            int idx = findTopLevelToken(condition, op);
            if (idx >= 0 && (bestOp == null || idx < bestIndex
                    || (idx == bestIndex && op.length() > bestOp.length()))) {
                bestIndex = idx;
                bestOp = op;
            }
        }
        if (bestOp == null) {
            throw new CommandDslParseException(
                "Malformed {if} condition, no comparison operator found: " + condition);
        }
        return new OperatorMatch(bestIndex, bestOp);
    }

    private int findTopLevelToken(String text, String token) {
        boolean inQuotes = false;
        for (int i = 0; i <= text.length() - token.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') { inQuotes = !inQuotes; continue; }
            if (!inQuotes && text.regionMatches(i, token, 0, token.length())) {
                return i;
            }
        }
        return -1;
    }
}
