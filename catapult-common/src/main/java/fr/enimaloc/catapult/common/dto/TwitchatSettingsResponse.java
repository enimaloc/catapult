package fr.enimaloc.catapult.common.dto;

public record TwitchatSettingsResponse(boolean enabled, String obsHost, Integer obsPort, boolean hasPassword, String widgetToken) {}
