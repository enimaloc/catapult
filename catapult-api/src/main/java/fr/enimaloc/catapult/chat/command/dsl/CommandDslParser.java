package fr.enimaloc.catapult.chat.command.dsl;

import fr.enimaloc.catapult.chat.command.ast.CommandAst;
import fr.enimaloc.catapult.chat.command.ast.CommandNode;
import fr.enimaloc.catapult.chat.command.ast.ForEachNode;
import fr.enimaloc.catapult.chat.command.ast.IfNode;
import fr.enimaloc.catapult.chat.command.ast.LiteralNode;
import fr.enimaloc.catapult.chat.command.ast.PlaceholderNode;
import fr.enimaloc.catapult.chat.command.ast.ServiceCallNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser for the command DSL:
 *   literal text, {path}, {path|fallback}, {ns#fn(arg, ...)},
 *   {if a == b}...{else}...{/if}, {for x in list}...{/for}.
 */
public class CommandDslParser {

    public CommandAst parse(String text) {
        DslCursor cursor = new DslCursor(text);
        return new CommandAst(parseSequence(cursor, null));
    }

    private List<CommandNode> parseSequence(DslCursor cursor, String stopTag) {
        List<CommandNode> nodes = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        while (!cursor.atEnd()) {
            if (stopTag != null && looksAheadAt(cursor, stopTag)) break;
            char c = cursor.peek();
            if (c == '{') {
                if (!literal.isEmpty()) {
                    nodes.add(new LiteralNode(literal.toString()));
                    literal.setLength(0);
                }
                nodes.add(parseBraceExpression(cursor));
            } else {
                literal.append(cursor.next());
            }
        }
        if (!literal.isEmpty()) {
            nodes.add(new LiteralNode(literal.toString()));
        }
        return nodes;
    }

    private boolean looksAheadAt(DslCursor cursor, String tag) {
        int start = cursor.position();
        return cursor.length() - start >= tag.length()
            && cursor.substring(start, start + tag.length()).equals(tag);
    }

    private CommandNode parseBraceExpression(DslCursor cursor) {
        int openPos = cursor.position();
        cursor.next(); // consume '{'
        int start = cursor.position();
        int depth = 1;
        while (!cursor.atEnd() && depth > 0) {
            char c = cursor.next();
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        if (depth > 0) {
            throw new CommandDslParseException("Unclosed '{' starting at position " + openPos);
        }
        String inner = cursor.substring(start, cursor.position() - 1);

        if (inner.startsWith("if ")) {
            return parseIf(cursor, inner.substring(3).trim());
        }
        if (inner.startsWith("for ")) {
            return parseFor(cursor, inner.substring(4).trim());
        }

        int parenIdx = inner.indexOf('(');
        if (parenIdx > 0 && inner.endsWith(")") && inner.contains("#")) {
            String head = inner.substring(0, parenIdx);
            String[] parts = head.split("#", 2);
            if (parts.length == 2) {
                String argsText = inner.substring(parenIdx + 1, inner.length() - 1);
                return new ServiceCallNode(parts[0], parts[1], parseArgs(argsText));
            }
        }

        int bar = inner.indexOf('|');
        String path = bar < 0 ? inner : inner.substring(0, bar);
        return new PlaceholderNode(path);
    }

    private CommandNode parseIf(DslCursor cursor, String condition) {
        String[] parts = condition.split("==", 2);
        CommandNode left = parseSingleValue(parts[0].trim());
        CommandNode right = parseSingleValue(parts[1].trim());

        List<CommandNode> thenBranch = parseSequence(cursor, "{else}");
        List<CommandNode> elseBranch = List.of();
        if (looksAheadAt(cursor, "{else}")) {
            cursor.position(cursor.position() + "{else}".length());
            elseBranch = parseSequence(cursor, "{/if}");
        }
        if (!looksAheadAt(cursor, "{/if}")) {
            throw new CommandDslParseException("Unclosed '{if}' block, expected '{/if}'");
        }
        cursor.position(cursor.position() + "{/if}".length());
        return new IfNode(left, "==", right, thenBranch, elseBranch);
    }

    private CommandNode parseFor(DslCursor cursor, String header) {
        String[] parts = header.split(" in ", 2);
        String bindingName = parts[0].trim();
        String listSource = parts[1].trim();
        List<CommandNode> body = parseSequence(cursor, "{/for}");
        if (!looksAheadAt(cursor, "{/for}")) {
            throw new CommandDslParseException("Unclosed '{for}' block, expected '{/for}'");
        }
        cursor.position(cursor.position() + "{/for}".length());
        return new ForEachNode(bindingName, listSource, body);
    }

    private CommandNode parseSingleValue(String token) {
        if (token.startsWith("\"") && token.endsWith("\"")) {
            return new LiteralNode(token.substring(1, token.length() - 1));
        }
        return new PlaceholderNode(token);
    }

    private List<CommandNode> parseArgs(String argsText) {
        List<CommandNode> args = new ArrayList<>();
        if (argsText.isBlank()) return args;
        for (String rawArg : splitTopLevelArgs(argsText)) {
            String arg = rawArg.trim();
            if (arg.startsWith("\"") && arg.endsWith("\"")) {
                args.add(new LiteralNode(arg.substring(1, arg.length() - 1)));
            } else {
                args.add(new PlaceholderNode(arg));
            }
        }
        return args;
    }

    /**
     * Splits a comma-separated argument list on top-level commas only,
     * ignoring commas that appear inside a {@code "..."} quoted literal.
     */
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
}
