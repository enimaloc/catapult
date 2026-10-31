package fr.enimaloc.catapult.chat.command.ast;

import java.util.List;

public record ForEachStatement(String bindingName, String listSource, List<Statement> body) implements Statement {
    @Override
    public String typeName() {
        return "for-each";
    }
}
