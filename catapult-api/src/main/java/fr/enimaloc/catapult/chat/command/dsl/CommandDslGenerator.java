package fr.enimaloc.catapult.chat.command.dsl;

import fr.enimaloc.catapult.chat.command.ast.CommandAst;
import fr.enimaloc.catapult.chat.command.ast.CommandNode;
import fr.enimaloc.catapult.chat.command.ast.LiteralNode;
import fr.enimaloc.catapult.chat.command.ast.PlaceholderNode;
import fr.enimaloc.catapult.chat.command.ast.ServiceCallNode;

import java.util.List;
import java.util.stream.Collectors;

public class CommandDslGenerator {

    public String generate(CommandAst ast) {
        StringBuilder out = new StringBuilder();
        for (CommandNode node : ast.nodes()) {
            out.append(generateNode(node));
        }
        return out.toString();
    }

    private String generateNode(CommandNode node) {
        return switch (node) {
            case LiteralNode literal -> literal.text();
            case PlaceholderNode placeholder -> "{" + placeholder.path() + "}";
            case ServiceCallNode call -> "{" + call.namespace() + "#" + call.function()
                + "(" + generateArgs(call.args()) + ")}";
            default -> throw new IllegalArgumentException("Unhandled node type: " + node.typeName());
        };
    }

    private String generateArgs(List<CommandNode> args) {
        return args.stream().map(this::generateArg).collect(Collectors.joining(", "));
    }

    private String generateArg(CommandNode arg) {
        return switch (arg) {
            case LiteralNode literal -> "\"" + literal.text() + "\"";
            case PlaceholderNode placeholder -> placeholder.path();
            default -> throw new IllegalArgumentException("Unsupported argument node: " + arg.typeName());
        };
    }
}
