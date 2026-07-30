package fr.enimaloc.catapult.chat.command.ast;

public record AssignStatement(String name, Expression expr) implements Statement {
    @Override
    public String typeName() {
        return "assign";
    }
}
