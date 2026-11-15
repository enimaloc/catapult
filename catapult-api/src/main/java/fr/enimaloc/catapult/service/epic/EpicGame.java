package fr.enimaloc.catapult.service.epic;

/**
 * A game seen in an Epic presence. {@code sourceId} is the EOS product id for an EOS game, or {@code "ns:"} followed
 * by the namespace for a non-EOS one (the prefix keeps a namespace from being mistaken for a product id).
 */
public record EpicGame(String sourceId, String name, boolean eos) {
}
