package fr.enimaloc.catapult.service.epic;

/**
 * A game seen in an Epic presence. {@code sourceId} is the EOS product id for an EOS game, {@code "ns:"} followed
 * by the namespace for a non-EOS one, or {@code "app:"} followed by the launcher's {@code running_app_id} for a game
 * only the launcher reports (the prefixes keep them from being mistaken for a product id).
 */
public record EpicGame(String sourceId, String name, boolean eos) {
}
