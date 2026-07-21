package fr.enimaloc.catapult.chat.command.ast;

import java.util.List;

public record ServiceCallNode(String namespace, String function, List<CommandNode> args) implements CommandNode {
    @Override
    public String typeName() {
        return "service-call";
    }
}
