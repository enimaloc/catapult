package fr.enimaloc.catapult.chat.command.ast;

public record LiteralExpr(String value, ValueType type) implements Expression {
    @Override
    public String typeName() {
        return "literal";
    }
}
