package fr.enimaloc.catapult.chat.command.ast;

import java.util.Map;

/** Groups several related values into one variable, e.g. {@code {name: "Valorant", price: 29.99}}. */
public record ObjectLiteralExpr(Map<String, Expression> properties) implements Expression {
    @Override
    public String typeName() {
        return "object-literal";
    }
}
