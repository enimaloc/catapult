package fr.enimaloc.catapult.chat.command.registry;

import fr.enimaloc.catapult.chat.command.ast.CommandNode;

/**
 * Plugs a new structural construct (if/for/future ones) into parsing,
 * text generation, JS compilation and Blockly without touching the
 * core dispatcher — see docs/specs/2026-07-21-chat-command-block-dsl-design.md#extensibility.
 */
public interface StructuralNodeType {
    String keyword();
    boolean handles(CommandNode node);
}
