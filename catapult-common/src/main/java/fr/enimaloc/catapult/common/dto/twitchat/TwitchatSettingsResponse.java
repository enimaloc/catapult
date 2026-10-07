package fr.enimaloc.catapult.common.dto.twitchat;

public record TwitchatSettingsResponse(boolean enabled, String obsHost, Integer obsPort, boolean hasPassword, String widgetToken) {}
