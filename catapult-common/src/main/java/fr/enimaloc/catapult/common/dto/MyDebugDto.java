package fr.enimaloc.catapult.common.dto;

/**
 * Whether the caller's own debug mode is on (it only tells the web to forward its browser-side errors), and the
 * share of their pages the browser then replays whole, from 0 to 1 (0 when the mode is off).
 */
public record MyDebugDto(boolean active, double replayRate) {
}
