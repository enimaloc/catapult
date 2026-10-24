package fr.enimaloc.catapult.common.dto.admin;

/**
 * What saving (or deleting) an admin game mapping did: the mapping (null once deleted), the bindings it changed,
 * and the live channels updated on Twitch or that Twitch refused.
 */
public record GameMappingResult(GameMappingDto mapping, int bindingsUpdated, int liveChannelsUpdated,
                                int liveChannelsFailed) {}
