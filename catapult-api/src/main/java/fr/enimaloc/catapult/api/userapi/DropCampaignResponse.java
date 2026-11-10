package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.service.drops.DropCampaign;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** A Twitch drops campaign running for your channel, as ttvdrops lists it. */
@Schema(description = "A Twitch drops campaign running for your channel")
public record DropCampaignResponse(
        @Schema(description = "The campaign's Twitch id") String id,
        @Schema(description = "Its name", example = "SEASON AURORA - WARFARE") String name,
        @Schema(description = "The Twitch category it runs on") String gameId,
        @Schema(description = "That category's name") String gameName,
        @Schema(description = "When it started") Instant startAt,
        @Schema(description = "When it ends") Instant endAt,
        @Schema(description = "Its page, null when it has none") String detailsUrl,
        @Schema(description = "Its image, null when it has none") String imageUrl) {

    static DropCampaignResponse of(DropCampaign campaign) {
        return new DropCampaignResponse(campaign.id(), campaign.name(), campaign.gameId(), campaign.gameName(),
                campaign.startAt(), campaign.endAt(), campaign.detailsUrl(), campaign.imageUrl());
    }
}
