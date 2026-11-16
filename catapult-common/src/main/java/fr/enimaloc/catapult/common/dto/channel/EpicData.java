package fr.enimaloc.catapult.common.dto.channel;

/** The owner's Epic link: {@code displayName} is the streamer's, {@code serviceAccountName} the bot's (while pending). */
public record EpicData(
        String status,
        String displayName,
        String serviceAccountName
) {
    public boolean connected() {
        return "ACCEPTED".equalsIgnoreCase(status);
    }
}
