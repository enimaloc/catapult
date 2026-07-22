package fr.enimaloc.catapult.chat.command.trace;

public record TraceEntry(String nodeType, String description, String resolvedValue, boolean error) {
}
