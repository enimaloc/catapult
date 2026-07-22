package fr.enimaloc.catapult.chat.command.ast;

/** Appends the resolved value onto the accumulated output — multiple prints concatenate, never overwrite. */
public record PrintStatement(Expression expr) implements Statement {
    @Override
    public String typeName() {
        return "print";
    }
}
