package fr.enimaloc.catapult.common.dto.channel;

import java.util.UUID;

/**
 * A title field's text to preview, with what it is the title of: {@code kind} TEMPLATE (for every game), BINDING
 * ({@code bindingId}), CUSTOM ({@code customBindingId}), NO_GAME or INCOMPLETE.
 */
public record TitlePreviewRequest(String template, String kind, UUID bindingId, UUID customBindingId) {}
