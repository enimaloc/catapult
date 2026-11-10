package fr.enimaloc.catapult.service.title;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * What a title template is rendered with: its variables' values and its conditions' ({@link TitleTemplate#CONDITIONS},
 * an absent one being false). {@code title} and {@code originalTitle} are read only when a chosen branch uses them;
 * the caller memoizes them. {@code dropName} and {@code dropEnd} are null without a running campaign.
 */
public record TitleContext(String game, String category, Supplier<Optional<String>> title,
                           Supplier<Optional<String>> originalTitle, Map<String, Boolean> conditions,
                           String dropName, String dropEnd) {
}
