package fr.enimaloc.catapult.chat.command.trace;

/**
 * A single recorded step of a command execution trace. {@code nodeType} is one of
 * {@code "placeholder"}, {@code "for-each"}, {@code "service-call"}, {@code "script"} (a
 * top-level execution error), {@code "var"} (a variable's value right after the statement that
 * set it — {@code description} is the variable name, {@code resolvedValue} its new value), or
 * {@code "if-branch"} (a branch decision — {@code description} is a human-readable rendering of
 * the compared condition, {@code resolvedValue} is {@code "then"} or {@code "else"}).
 */
public record TraceEntry(String nodeType, String description, String resolvedValue, boolean error) {
}
