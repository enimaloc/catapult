package fr.enimaloc.catapult.chat.command.js;

import fr.enimaloc.catapult.chat.command.ast.CommandAst;
import fr.enimaloc.catapult.chat.command.ast.CommandNode;
import fr.enimaloc.catapult.chat.command.ast.ForEachNode;
import fr.enimaloc.catapult.chat.command.ast.IfNode;
import fr.enimaloc.catapult.chat.command.ast.LiteralNode;
import fr.enimaloc.catapult.chat.command.ast.PlaceholderNode;
import fr.enimaloc.catapult.chat.command.ast.ServiceCallNode;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Compiles a {@link CommandAst} into a JavaScript program string that assembles a
 * {@code result} string via a {@code ctx} object exposing {@code ctx.placeholder(path)},
 * {@code ctx.call(namespace, function, ...args)} and {@code ctx.list(name)}.
 *
 * <p>This only produces JS source text; it does not execute anything (execution happens
 * in a sandboxed engine in a later task).
 */
public class JsCompiler {

    public String compile(CommandAst ast) {
        StringBuilder js = new StringBuilder();
        js.append("let result = \"\";\n");
        for (CommandNode node : ast.nodes()) {
            appendNode(node, js, Set.of());
        }
        js.append("return result;\n");
        return js.toString();
    }

    private void appendNode(CommandNode node, StringBuilder js, Set<String> bindings) {
        switch (node) {
            case LiteralNode n -> js.append("result += \"").append(escape(n.text())).append("\";\n");
            case PlaceholderNode n -> js.append("result += ").append(compileValue(n, bindings)).append(";\n");
            case ServiceCallNode n -> js.append("result += ").append(compileCall(n, bindings)).append(";\n");
            case IfNode n -> {
                js.append("if (").append(compileValue(n.left(), bindings)).append(" ").append(n.operator())
                    .append("= ").append(compileValue(n.right(), bindings)).append(") {\n");
                for (CommandNode child : n.thenBranch()) appendNode(child, js, bindings);
                if (!n.elseBranch().isEmpty()) {
                    js.append("} else {\n");
                    for (CommandNode child : n.elseBranch()) appendNode(child, js, bindings);
                }
                js.append("}\n");
            }
            case ForEachNode n -> {
                js.append("for (const ").append(n.bindingName()).append(" of ctx.list(\"")
                    .append(n.listSource()).append("\")) {\n");
                Set<String> childBindings = new HashSet<>(bindings);
                childBindings.add(n.bindingName());
                for (CommandNode child : n.body()) appendNode(child, js, childBindings);
                js.append("}\n");
            }
            default -> throw new IllegalArgumentException("Unhandled node type: " + node.typeName());
        }
    }

    private String compileCall(ServiceCallNode n, Set<String> bindings) {
        String args = n.args().stream().map(a -> compileValue(a, bindings)).collect(Collectors.joining(", "));
        return "ctx.call(\"" + escape(n.namespace()) + "\", \"" + escape(n.function()) + "\""
            + (args.isEmpty() ? "" : ", " + args) + ")";
    }

    private String compileValue(CommandNode node, Set<String> bindings) {
        return switch (node) {
            case LiteralNode n -> "\"" + escape(n.text()) + "\"";
            case PlaceholderNode n -> bindings.contains(n.path()) ? n.path() : "ctx.placeholder(\"" + escape(n.path()) + "\")";
            case ServiceCallNode n -> compileCall(n, bindings);
            default -> throw new IllegalArgumentException("Unsupported value node: " + node.typeName());
        };
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
