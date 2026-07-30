package fr.enimaloc.catapult.chat.command.ast;

public record VarDeclStatement(String name, ValueType type, Expression init) implements Statement {
    @Override
    public String typeName() {
        return "var-decl";
    }
}
