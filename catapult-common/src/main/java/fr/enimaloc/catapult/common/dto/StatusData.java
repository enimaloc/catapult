package fr.enimaloc.catapult.common.dto;

public record StatusData(
        String channelUsername,
        boolean isOwner,
        boolean botEnabled,
        boolean isLive,
        GameDto currentGame
) {}
