package fr.enimaloc.catapult.common.dto;

public record SteamKeyStatus(String id, String masked, String owner, boolean blocked, long blockedForSeconds) {}
