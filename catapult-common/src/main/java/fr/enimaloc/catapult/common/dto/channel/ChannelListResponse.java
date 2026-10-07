package fr.enimaloc.catapult.common.dto.channel;

import java.util.List;

public record ChannelListResponse(String viewerTwitchId, List<ChannelDto> channels) {}