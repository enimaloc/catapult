package fr.enimaloc.catapult.common.dto;

/** Whether the caller's own debug mode is on (it only tells the web to forward its browser-side errors). */
public record MyDebugDto(boolean active) {
}
