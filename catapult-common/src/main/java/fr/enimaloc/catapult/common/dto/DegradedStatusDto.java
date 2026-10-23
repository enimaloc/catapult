package fr.enimaloc.catapult.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Whether catapult runs degraded, with the intervals it then applies. The intervals are
 * left out when they do not apply (not degraded, or the feature behind them is off).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DegradedStatusDto(boolean degraded, Integer gameDetectionIntervalSeconds, Integer minecraftFriendsSyncMinutes) {}
