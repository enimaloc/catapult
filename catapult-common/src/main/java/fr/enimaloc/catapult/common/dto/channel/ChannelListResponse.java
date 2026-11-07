package fr.enimaloc.catapult.common.dto.channel;

import java.util.List;

/**
 * The channels the member can act on, and whether premium is shown to them (experiment {@code premium.ui});
 * boxed and false when absent, as an older catapult-api sends it during a rolling deploy.
 */
public record ChannelListResponse(String viewerTwitchId, List<ChannelDto> channels, Boolean premiumUi) {

    public ChannelListResponse {
        premiumUi = Boolean.TRUE.equals(premiumUi);
    }

    public ChannelListResponse(String viewerTwitchId, List<ChannelDto> channels) {
        this(viewerTwitchId, channels, false);
    }
}
