package fr.enimaloc.catapult.chat.command.ast;

import java.util.List;

public record ServiceCallExpr(String namespace, String function, List<Expression> args) implements Expression {
    @Override
    public String typeName() {
        return "service-call";
    }
}
