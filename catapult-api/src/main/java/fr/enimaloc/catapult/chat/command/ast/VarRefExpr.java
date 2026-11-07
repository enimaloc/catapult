package fr.enimaloc.catapult.chat.command.ast;

public record VarRefExpr(String name) implements Expression {
    @Override
    public String typeName() {
        return "var-ref";
    }
}
