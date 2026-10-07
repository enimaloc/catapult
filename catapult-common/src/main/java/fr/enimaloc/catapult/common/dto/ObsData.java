package fr.enimaloc.catapult.common.dto;

/**
 * OBS-websocket settings for the Twitchat relay. The password itself never leaves the
 * API — {@code hasPassword} only says whether one is stored.
 */
public record ObsData(
        boolean connected,
        String host,
        Integer port,
        boolean hasPassword
) {
}
