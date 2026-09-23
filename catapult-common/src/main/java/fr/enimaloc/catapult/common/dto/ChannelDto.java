package fr.enimaloc.catapult.common.dto;

import java.util.UUID;

public record ChannelDto(UUID id, String twitchId, String twitchUsername, String profileImageUrl, boolean live) {}
