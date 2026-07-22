package fr.enimaloc.catapult.chat.command.ast;

import java.util.List;

public record IfNode(CommandNode left, String operator, CommandNode right,
                      List<CommandNode> thenBranch, List<CommandNode> elseBranch) implements CommandNode {
    @Override
    public String typeName() {
        return "if";
    }
}
