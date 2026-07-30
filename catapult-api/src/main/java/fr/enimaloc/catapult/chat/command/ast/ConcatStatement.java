package fr.enimaloc.catapult.chat.command.ast;

/** Sugar for {@code name = name + expr}. String-typed variables only — enforced by the DSL parser. */
public record ConcatStatement(String name, Expression expr) implements Statement {
    @Override
    public String typeName() {
        return "concat";
    }
}
