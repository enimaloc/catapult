package fr.enimaloc.catapult.chat.command.ast;

import java.util.List;

public record IfStatement(BinaryExpr condition, List<Statement> thenBranch, List<Statement> elseBranch)
    implements Statement {
    @Override
    public String typeName() {
        return "if";
    }
}
