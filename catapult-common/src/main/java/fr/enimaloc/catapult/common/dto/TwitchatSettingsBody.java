package fr.enimaloc.catapult.common.dto;

public record TwitchatSettingsBody(boolean enabled, String obsHost, Integer obsPort, String obsPassword) {}
