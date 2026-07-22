package fr.enimaloc.catapult.chat.command.ast;

import java.util.List;

public record CommandAst(List<Statement> statements) {
    /**
     * @deprecated Use statements() instead. For backward compatibility during migration.
     */
    @Deprecated(forRemoval = true)
    public List<CommandNode> nodes() {
        return statements.stream()
            .filter(CommandNode.class::isInstance)
            .map(CommandNode.class::cast)
            .toList();
    }
}
