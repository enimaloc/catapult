package fr.enimaloc.catapult.common.dto.twitchat;

public record TwitchatSettingsBody(boolean enabled, String obsHost, Integer obsPort, String obsPassword) {}
