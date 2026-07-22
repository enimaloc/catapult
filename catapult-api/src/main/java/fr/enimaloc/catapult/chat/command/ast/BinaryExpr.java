package fr.enimaloc.catapult.chat.command.ast;

public record BinaryExpr(Expression left, String operator, Expression right) implements Expression {
    @Override
    public String typeName() {
        return "binary";
    }
}
