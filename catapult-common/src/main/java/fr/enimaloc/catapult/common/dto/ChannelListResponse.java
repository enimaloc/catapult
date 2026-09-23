package fr.enimaloc.catapult.common.dto;

import java.util.List;

public record ChannelListResponse(String viewerTwitchId, List<ChannelDto> channels) {}