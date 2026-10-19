package fr.enimaloc.catapult.common.dto.admin;

/** A trigger warning as catapult-api's {@code TwDefinition} serializes it (its description may be null). */
public record TwDefinitionDto(String id, String label, String description, boolean enabled, int sortOrder) {}
