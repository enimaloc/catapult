package fr.enimaloc.catapult.chat.command.dsl;

/**
 * Thrown when the command DSL text cannot be parsed, e.g. an unbalanced
 * or missing closing brace.
 */
public class CommandDslParseException extends RuntimeException {

    public CommandDslParseException(String message) {
        super(message);
    }
}
