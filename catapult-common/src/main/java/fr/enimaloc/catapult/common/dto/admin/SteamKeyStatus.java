package fr.enimaloc.catapult.common.dto.admin;

public record SteamKeyStatus(String id, String masked, String owner, boolean blocked, long blockedForSeconds) {}
