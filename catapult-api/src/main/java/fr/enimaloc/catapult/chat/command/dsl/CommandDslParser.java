package fr.enimaloc.catapult.chat.command.dsl;

import fr.enimaloc.catapult.chat.command.ast.CommandAst;
import fr.enimaloc.catapult.chat.command.ast.CommandNode;
import fr.enimaloc.catapult.chat.command.ast.LiteralNode;
import fr.enimaloc.catapult.chat.command.ast.PlaceholderNode;
import fr.enimaloc.catapult.chat.command.ast.ServiceCallNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser for the command DSL:
 *   literal text, {path}, {path|fallback}, {ns#fn(arg, ...)}.
 * Structural keywords ({if}/{for}) are added in Task 8 via StructuralNodeTypeRegistry.
 */
public class CommandDslParser {

    public CommandAst parse(String text) {
        DslCursor cursor = new DslCursor(text);
        return new CommandAst(parseSequence(cursor));
    }

    private List<CommandNode> parseSequence(DslCursor cursor) {
        List<CommandNode> nodes = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        while (!cursor.atEnd()) {
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
