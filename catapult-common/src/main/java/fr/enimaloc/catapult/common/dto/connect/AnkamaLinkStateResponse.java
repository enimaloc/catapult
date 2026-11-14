package fr.enimaloc.catapult.common.dto.connect;

/** A user's Ankama link: {@code nameTag} is the streamer's, {@code serviceAccountTag} the bot's (while pending). */
public record AnkamaLinkStateResponse(String status, String nameTag, String serviceAccountTag) {
}
