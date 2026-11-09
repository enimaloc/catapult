package fr.enimaloc.catapult.common.dto.channel;

/**
 * The channel's OBS-websocket settings. A null {@code password} keeps the stored one;
 * a blank {@code host} or null {@code port} falls back to the defaults.
 */
public record ObsSettingsRequest(boolean enabled, String host, Integer port, String password) {}
