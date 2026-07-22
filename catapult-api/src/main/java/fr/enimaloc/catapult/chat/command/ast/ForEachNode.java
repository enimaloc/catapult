package fr.enimaloc.catapult.chat.command.ast;

import java.util.List;

public record ForEachNode(String bindingName, String listSource, List<CommandNode> body) implements CommandNode {
    @Override
    public String typeName() {
        return "for-each";
    }
}
