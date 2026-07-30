package fr.enimaloc.catapult.chat.command.ast;

public record ContextGetExpr(String path) implements Expression {
    @Override
    public String typeName() {
        return "context-get";
    }
}
