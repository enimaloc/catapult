package fr.enimaloc.catapult.dto.integration;

import java.util.List;

/** One connection card of the channel's integrations tab: {@code id} is its {@code data-conn}. */
public record IntegrationDto(String id, Message name, List<IntegrationNode> nodes) {
    public IntegrationDto {
        nodes = List.copyOf(nodes);
    }
}
