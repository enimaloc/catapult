package fr.enimaloc.catapult.common.dto.channel;

public record StatusData(
        String channelUsername,
        boolean isOwner,
        boolean botEnabled,
        boolean isLive,
        GameDto currentGame
) {}
