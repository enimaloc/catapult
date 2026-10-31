package fr.enimaloc.catapult.chat.command.ast;

/** Root marker for every AST node — statements (effects) and expressions (values). */
public interface Node {
    String typeName();
}
