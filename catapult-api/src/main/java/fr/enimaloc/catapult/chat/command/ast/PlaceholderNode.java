package fr.enimaloc.catapult.chat.command.ast;

public record PlaceholderNode(String path) implements CommandNode {
    @Override
    public String typeName() {
        return "placeholder";
    }
}
