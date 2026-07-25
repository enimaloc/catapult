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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Recursive-descent parser for the imperative command DSL — see
 * docs/specs/2026-07-21-chat-command-block-dsl-design.md#text-dsl-grammar.
 * Statements: literal text (implicit print), {@code {var name = expr}}, {@code {name = expr}},
 * {@code {name = name + expr}} (concat), {@code {print expr}}, {@code {if L OP R}...{else}...{/if}},
 * {@code {for x in list}...{/for}}. Backward-compat shorthand: bare {@code {path}} (context path,
 * contains '#'), bare {@code {name}} (variable reference, no '#'), bare {@code {ns#fn(args)}}
 * (service call) all sugar for an implicit print. {@code ctx.game.name} is sugar for the context
 * path {@code game#name}; {@code ctx.settings.<key>} reads a streamer-defined setting (see
 * {@code ChatCommandSetting}); any other dot-chain ({@code myObj.name}, chainable) is property
 * access on a variable, equivalent to {@code get(myObj, "name")}.
 */
public class CommandDslParser {

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern NUMBER = Pattern.compile("^-?\\d+(\\.\\d+)?$");
    private static final Pattern ARG_INDEX = Pattern.compile("^\\d+$");
    private static final List<String> COMPARISON_OPERATORS = List.of("!=", "==", "<=", ">=", "<", ">");

    // Legacy templates (pre-V59__placeholder_hash_separator.sql) used '.' instead of '#' as the
    // context-path separator (e.g. {game.name} instead of {game#name}). That migration rewrote
    // stored `template` text once, but isn't guaranteed to have touched every row (fallbacks
    // entered afterwards, edge cases its regex didn't match) — the parser tolerates both forms
    // so a still-legacy-shaped command degrades gracefully instead of throwing on every edit/dispatch.
    private static final Pattern LEGACY_DOT_PATH = Pattern.compile("^[a-z_]+(\\.[a-z_]+)+$");

    private static String normalizeLegacyDotPath(String path) {
        return LEGACY_DOT_PATH.matcher(path).matches() ? path.replace('.', '#') : path;
    }

    // General dot-chain syntax: {@code ctx.game.name} is sugar for the context path
    // "game#name"; any other dot-chain ({@code myObj.name}, chainable) is property access on a
    // variable, equivalent to {@code get(myObj, "name")}. Only reachable outside the bare-tag
    // shorthand's legacy-dot-path branch, so it never competes with pre-V59 templates.
    private static final Pattern DOT_CHAIN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$");

    private Expression parseDotChain(String text) {
        String[] segments = text.split("\\.");
        if (segments[0].equals("ctx") && segments.length > 1 && segments[1].equals("settings")) {
            if (segments.length != 3) {
                throw new CommandDslParseException(
                    "ctx.settings.<key> takes exactly one key segment (settings are flat, not nested): " + text);
            }
            return new SettingGetExpr(segments[2]);
        }
        if (segments[0].equals("ctx")) {
            return new ContextGetExpr(String.join("#", java.util.Arrays.copyOfRange(segments, 1, segments.length)));
        }
        Expression current = new VarRefExpr(segments[0]);
        for (int i = 1; i < segments.length; i++) {
            current = new PropertyGetExpr(current, segments[i]);
        }
        return current;
    }

    /** {@code arg(N)} or {@code arg(N, "default")} reads one word of the chat command's own
     *  arguments — N must be a literal non-negative integer (no signs, no decimals, no
     *  variables): {@code arg(-1)}, {@code arg(x)} and {@code arg(1.5)} are all rejected here
     *  rather than silently misparsed. The optional default must likewise be a literal string
     *  (no variables, no nested expressions) — {@code arg(0, "everyone")}. */
    private Expression parseArgGet(String text) {
        String inner = text.substring(4, text.length() - 1).trim();
        List<String> parts = splitTopLevelArgs(inner);
        if (parts.isEmpty() || parts.size() > 2) {
            throw new CommandDslParseException(
                "arg(N) or arg(N, \"default\") expected: " + text);
        }
        String indexText = parts.get(0).trim();
        if (!ARG_INDEX.matcher(indexText).matches()) {
            throw new CommandDslParseException(
                "arg(N) requires a literal non-negative integer index: " + text);
        }
        int index = Integer.parseInt(indexText);
        if (parts.size() == 1) {
            return new ArgGetExpr(index, null);
        }
        String defaultText = parts.get(1).trim();
        if (!(defaultText.startsWith("\"") && defaultText.endsWith("\"") && defaultText.length() >= 2)) {
            throw new CommandDslParseException(
                "arg(N, default) requires a literal string default: " + text);
        }
        return new ArgGetExpr(index, defaultText.substring(1, defaultText.length() - 1));
    }

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
        if (inner.startsWith("arg(") && inner.endsWith(")")) {
            return new PrintStatement(parseArgGet(inner));
        }
        int bar = inner.indexOf('|');
        String rawPath = bar < 0 ? inner : inner.substring(0, bar);
        if (rawPath.startsWith("ctx.") && DOT_CHAIN.matcher(rawPath).matches()) {
            return new PrintStatement(parseDotChain(rawPath));
        }
        String path = normalizeLegacyDotPath(rawPath);
        if (path.contains("#")) {
            return new PrintStatement(new ContextGetExpr(path));
        }
        if (IDENTIFIER.matcher(path).matches()) {
            return new PrintStatement(new VarRefExpr(path));
        }
        if (DOT_CHAIN.matcher(path).matches()) {
            return new PrintStatement(parseDotChain(path));
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
        ValueType type = switch (init) {
            case LiteralExpr literal -> literal.type();
            case ObjectLiteralExpr ignored -> ValueType.OBJECT;
            default -> ValueType.STRING;
        };
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
            return parseGetExpression(text.substring(4, text.length() - 1));
        }
        if (text.startsWith("arg(") && text.endsWith(")")) {
            return parseArgGet(text);
        }
        if (text.startsWith("{") && text.endsWith("}")) {
            return parseObjectLiteral(text.substring(1, text.length() - 1).trim());
        }
        // A trailing ".identifier" at paren/quote depth 0 is property access on WHATEVER comes
        // before it — not just a plain identifier chain (that's DOT_CHAIN below, which a bare
        // "game.name" already matches whole and doesn't need peeling for). This is what makes
        // e.g. steam#getGame(id, ctx.settings.lang).short_description parse at all: without it,
        // tryParseServiceCall below only recognizes a call when the ENTIRE text is "ns#fn(args)"
        // (must end with ")"), so a trailing .property made it fall through to the "text contains
        // '#' -> ContextGetExpr" catch-all, silently building a garbage context path instead of
        // throwing — the previous, unparseable text this method now generates for such AST nodes.
        if (!DOT_CHAIN.matcher(text).matches()) {
            int trailingDot = findTopLevelTrailingDot(text);
            if (trailingDot > 0) {
                String property = text.substring(trailingDot + 1).trim();
                if (IDENTIFIER.matcher(property).matches()) {
                    Expression target = parseExpression(text.substring(0, trailingDot).trim());
                    return new PropertyGetExpr(target, property);
                }
            }
        }
        ServiceCallExpr call = tryParseServiceCall(text);
        if (call != null) {
            return call;
        }
        if (DOT_CHAIN.matcher(text).matches()) {
            return parseDotChain(text);
        }
        if (text.contains("#")) {
            return new ContextGetExpr(text);
        }
        if (!IDENTIFIER.matcher(text).matches()) {
            throw new CommandDslParseException("Malformed expression: " + token);
        }
        return new VarRefExpr(text);
    }

    /** Index of the last {@code '.'} at paren/quote depth 0, or -1 if none — used to peel a
     *  trailing {@code .property} off of an arbitrary expression head (see {@link #parseExpression}). */
    private int findTopLevelTrailingDot(String text) {
        boolean inQuotes = false;
        int depth = 0;
        int lastDot = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (!inQuotes && (c == '(' || c == '{')) {
                depth++;
            } else if (!inQuotes && (c == ')' || c == '}')) {
                depth--;
            } else if (!inQuotes && depth == 0 && c == '.') {
                lastDot = i;
            }
        }
        return lastDot;
    }

    /** {@code get(path)} reads a context placeholder (also accepts {@code ctx.a.b} sugar);
     *  {@code get(obj, "property")} reads an object property. */
    private Expression parseGetExpression(String argsText) {
        List<String> args = splitTopLevelArgs(argsText);
        if (args.size() == 1) {
            String arg = args.get(0).trim();
            if (arg.startsWith("ctx.") && DOT_CHAIN.matcher(arg).matches()) {
                return parseDotChain(arg);
            }
            return new ContextGetExpr(arg);
        }
        if (args.size() == 2) {
            Expression target = parseExpression(args.get(0));
            String propertyToken = args.get(1).trim();
            String property = (propertyToken.startsWith("\"") && propertyToken.endsWith("\"") && propertyToken.length() >= 2)
                ? propertyToken.substring(1, propertyToken.length() - 1) : propertyToken;
            return new PropertyGetExpr(target, property);
        }
        throw new CommandDslParseException(
            "Malformed get(...) expression, expected get(path) or get(obj, \"property\"): get(" + argsText + ")");
    }

    /** {@code { key: expr, key2: expr2, ... }} groups several related values into one variable. */
    private Expression parseObjectLiteral(String inner) {
        Map<String, Expression> properties = new LinkedHashMap<>();
        if (!inner.isBlank()) {
            for (String rawEntry : splitTopLevelArgs(inner)) {
                String entry = rawEntry.trim();
                int colon = entry.indexOf(':');
                if (colon <= 0) {
                    throw new CommandDslParseException("Malformed object property, expected 'key: value': " + entry);
                }
                String key = entry.substring(0, colon).trim();
                if (!IDENTIFIER.matcher(key).matches()) {
                    throw new CommandDslParseException("Invalid object property name: " + key);
                }
                properties.put(key, parseExpression(entry.substring(colon + 1)));
            }
        }
        return new ObjectLiteralExpr(properties);
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

    /**
     * Splits a comma-separated argument/property list on top-level commas, ignoring commas
     * inside quotes or nested {@code {}}/{@code ()} — needed once object literals and
     * property-get calls can nest inside service-call args or other object literals
     * (e.g. {@code {a: {b: 1, c: 2}, d: 3}} must split into 2 top-level entries, not 3).
     */
    private List<String> splitTopLevelArgs(String argsText) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        int depth = 0;
        for (int i = 0; i < argsText.length(); i++) {
            char c = argsText.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
            } else if (!inQuotes && (c == '{' || c == '(')) {
                depth++;
                current.append(c);
            } else if (!inQuotes && (c == '}' || c == ')')) {
                depth--;
                current.append(c);
            } else if (c == ',' && !inQuotes && depth == 0) {
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
