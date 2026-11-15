package fr.enimaloc.catapult.common.dto.connect;

/** A user's Epic link: {@code displayName} is the streamer's, {@code serviceAccountName} the bot's (while pending). */
public record EpicLinkStateResponse(String status, String displayName, String serviceAccountName) {
}
