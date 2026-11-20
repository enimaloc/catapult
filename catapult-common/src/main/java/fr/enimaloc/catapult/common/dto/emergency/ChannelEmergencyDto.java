package fr.enimaloc.catapult.common.dto.emergency;

import java.util.List;

/**
 * A channel's emergency view: whether its bot is on, its own stops, and the global stops that reach a provider it
 * uses (an {@code ALL} stop always does).
 */
public record ChannelEmergencyDto(boolean botEnabled, List<EmergencyStopDto> channelStops,
                                  List<EmergencyStopDto> globalStopsOnUsedProviders) {}
