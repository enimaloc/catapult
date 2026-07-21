package fr.enimaloc.catapult.chat.command.ast;

public record LiteralNode(String text) implements CommandNode {
    @Override
    public String typeName() {
        return "literal";
    }
}
