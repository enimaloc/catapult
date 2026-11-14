package fr.enimaloc.catapult.dto.integration;

import java.util.List;

/**
 * One connection card of the channel's integrations tab: {@code id} is its {@code data-conn}. A {@code wide} card spans
 * the tab's grid, below the others: more than two buttons at once, or a form that needs the width.
 */
public record IntegrationDto(String id, Message name, List<IntegrationNode> nodes, boolean wide) {
    public IntegrationDto {
        nodes = List.copyOf(nodes);
    }
}
