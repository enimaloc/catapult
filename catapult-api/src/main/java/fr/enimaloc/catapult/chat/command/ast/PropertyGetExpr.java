package fr.enimaloc.catapult.chat.command.ast;

/** Reads a named property off an object-valued expression, e.g. {@code get(myObj, "name")}. */
public record PropertyGetExpr(Expression target, String property) implements Expression {
    @Override
    public String typeName() {
        return "property-get";
    }
}
